import json
import os
import secrets
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Optional, Tuple
from urllib.parse import parse_qs, urlparse

import requests
from bs4 import BeautifulSoup


ROOT = Path(__file__).resolve().parent
ACCOUNTS_FILE = ROOT / "accounts.json"
STATE_FILE = ROOT / "state.json"
PAIR_CODE_FILE = ROOT / "pair_code.txt"
PAIRED_DEVICES_FILE = ROOT / "paired_devices.json"
PORT = int(os.getenv("PORT", "39049"))
HOST = os.getenv("HOST", "127.0.0.1")
POLL_SECONDS = max(0.25, float(os.getenv("POLL_SECONDS", "0.75")))
HOURS_REFRESH_SECONDS = max(30, int(os.getenv("HOURS_REFRESH_SECONDS", "120")))
LONG_POLL_TIMEOUT = max(5, min(55, int(os.getenv("LONG_POLL_TIMEOUT", "45"))))
MAX_PENDING_OUT_HOURS = 16
MAX_BODY_BYTES = 64 * 1024
PUBLIC_BASE_URL = os.getenv("PUBLIC_BASE_URL", "")

FILE_LOCK = threading.RLock()
STATE_CONDITION = threading.Condition(threading.RLock())
TOKEN_LOCK = threading.RLock()
PAIR_ATTEMPT_LOCK = threading.Lock()
STATE_CACHE = {}
TOKEN_CACHE = {}
HTTP_SESSIONS = {}
HOURS_LAST_FETCH = {}
PAIR_ATTEMPTS = {}
PAIR_CODE = ""
LAST_CYCLE_AT = 0.0
LAST_CYCLE_SECONDS = 0.0


def read_json(path: Path, default):
    with FILE_LOCK:
        if not path.exists():
            return default
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return default


def write_json(path: Path, data):
    raw = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    temporary = path.with_suffix(path.suffix + ".tmp")
    with FILE_LOCK:
        temporary.write_text(raw, encoding="utf-8")
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
        os.chmod(path, 0o600)


def initialize_json_file(path: Path):
    if not path.exists() or not path.read_text(encoding="utf-8").strip():
        write_json(path, {})
    else:
        os.chmod(path, 0o600)


def comparable_state(state: dict) -> dict:
    payload = dict(state or {})
    payload.pop("checked_at", None)
    return payload


def resolve_pair_code() -> str:
    configured = os.getenv("CARDINAL_PUNCH_PAIR_CODE", "").strip()
    if configured and len(configured) < 10:
        configured = ""
    if configured:
        code = configured
    elif PAIR_CODE_FILE.exists() and len(PAIR_CODE_FILE.read_text(encoding="utf-8").strip()) >= 10:
        code = PAIR_CODE_FILE.read_text(encoding="utf-8").strip()
    else:
        code = secrets.token_urlsafe(12)
    PAIR_CODE_FILE.write_text(code, encoding="utf-8")
    os.chmod(PAIR_CODE_FILE, 0o600)
    return code


def session_for(account_id: str) -> requests.Session:
    with TOKEN_LOCK:
        if account_id not in HTTP_SESSIONS:
            session = requests.Session()
            session.headers.update({"Accept": "application/json", "User-Agent": "CardinalPunchWatch/2.0"})
            HTTP_SESSIONS[account_id] = session
        return HTTP_SESSIONS[account_id]


def fetch_oauth_token(account_id: str, account: dict, force=False) -> str:
    now = time.time()
    with TOKEN_LOCK:
        cached = TOKEN_CACHE.get(account_id)
        if not force and cached and cached[1] - 60 > now:
            return cached[0]

        response = session_for(account_id).post(
            account["api_url"].rstrip("/") + "/oauth/token",
            data={
                "username": account["company_code"],
                "password": account["company_code"],
                "grant_type": "password",
            },
            timeout=(5, 10),
        )
        response.raise_for_status()
        payload = response.json()
        token = payload["access_token"]
        TOKEN_CACHE[account_id] = (token, now + int(payload.get("expires_in", 3600)))
        return token


def fetch_user(account_id: str, account: dict) -> dict:
    for attempt in range(2):
        token = fetch_oauth_token(account_id, account, force=attempt > 0)
        response = session_for(account_id).get(
            account["api_url"].rstrip("/") + "/Parameters/User",
            params={"NIP": account["nip"]},
            headers={"Authorization": "Bearer " + token},
            timeout=(5, 10),
        )
        if response.status_code != 401:
            response.raise_for_status()
            return response.json()
    raise requests.HTTPError("oauth_rejected")


def fetch_hours(account_id: str, account: dict) -> Tuple[str, list, str, str]:
    response = session_for(account_id).get(
        account["site_path"].rstrip("/") + "/consult-mobile.asp",
        params={
            "QLangue": "fr",
            "pinOverride": account["nip"],
            "noEmployeOverride": account["user_id"],
        },
        timeout=(5, 12),
    )
    response.raise_for_status()
    soup = BeautifulSoup(response.text, "html.parser")
    bank = soup.select_one(".current-week .mal b")
    bank_hours = bank.get_text(" ", strip=True) if bank else ""
    today_label = datetime.now().strftime("%d").lstrip("0")
    target_cell = None
    for cell in soup.select("tbody.role-body.mes-heures tr.extra-row td.calendar_day"):
        label = cell.select_one("p.day")
        if label and label.get_text(strip=True).lstrip("0") == today_label:
            target_cell = cell
            break
    shifts, regular, overtime = [], "00:00", "00:00"
    if target_cell is not None:
        shifts = [span.get_text(" ", strip=True) for span in target_cell.select(".time-cell.temps span")]
        regular_span = target_cell.select_one(".time-cell.temps-reg span")
        overtime_span = target_cell.select_one(".time-cell.temps-supp span")
        regular = regular_span.get_text(" ", strip=True) if regular_span else regular
        overtime = overtime_span.get_text(" ", strip=True) if overtime_span else overtime
    HOURS_LAST_FETCH[account_id] = time.monotonic()
    return bank_hours, shifts, regular, overtime


def infer_punch_kind(last_punch: dict, shifts: list) -> str:
    check_type = str(last_punch.get("CheckType", ""))
    if check_type in {"1", "3"}:
        return "entry"
    if check_type in {"2", "4"}:
        return "exit"
    check_time = str(last_punch.get("CheckTime", ""))
    hhmm = check_time[11:16]
    latest = "unknown"
    for shift in shifts:
        parts = [part.strip() for part in shift.split("-")]
        if parts and parts[0]:
            latest = "entry"
            if hhmm == parts[0][:5]:
                return latest
        if len(parts) > 1 and parts[1]:
            latest = "exit"
            if hhmm == parts[1][:5]:
                return latest
    return latest


def punch_epoch_ms(check_time: str) -> int:
    try:
        return int(datetime.fromisoformat(check_time.replace(" ", "T")).timestamp() * 1000)
    except (TypeError, ValueError):
        return int(time.time() * 1000)


def inspect_account(account_id: str, account: dict, previous: dict) -> dict:
    try:
        user = fetch_user(account_id, account)
        last_punch = user.get("LastPunch") or {}
        check_time = str(last_punch.get("CheckTime") or "")
        signature = "{}|{}|{}".format(check_time, last_punch.get("CheckType", ""), last_punch.get("Note", ""))
        signature_changed = signature != previous.get("last_punch_signature", "")
        refresh_hours = (
            not previous
            or signature_changed
            or previous.get("today") != datetime.now().strftime("%Y-%m-%d")
            or time.monotonic() - HOURS_LAST_FETCH.get(account_id, 0) >= HOURS_REFRESH_SECONDS
        )
        if refresh_hours:
            bank, shifts, regular, overtime = fetch_hours(account_id, account)
        else:
            bank = previous.get("bank_hours", "")
            shifts = previous.get("today_shifts", [])
            regular = previous.get("regular_hours", "00:00")
            overtime = previous.get("overtime_hours", "00:00")

        kind = infer_punch_kind(last_punch, shifts)
        pending = int(previous.get("pending_out_since", 0) or 0)
        if signature and signature_changed:
            if kind == "entry":
                pending = punch_epoch_ms(check_time)
            elif kind == "exit":
                pending = 0
        missing = bool(pending and int(time.time() * 1000) - pending >= MAX_PENDING_OUT_HOURS * 3_600_000)
        return {
            "account_id": account_id,
            "full_name": user.get("Name", account.get("full_name", "")),
            "last_punch_signature": signature,
            "last_punch_time": check_time,
            "last_punch_kind": kind,
            "bank_hours": bank,
            "today": datetime.now().strftime("%Y-%m-%d"),
            "today_shifts": shifts,
            "regular_hours": regular,
            "overtime_hours": overtime,
            "pending_out_since": pending,
            "missing_punch": missing,
            "checked_at": datetime.now().isoformat(),
        }
    except Exception as exc:
        state = dict(previous or {})
        state.update({
            "account_id": account_id,
            "error": type(exc).__name__,
            "checked_at": datetime.now().isoformat(),
        })
        return state


def monitor_loop():
    global LAST_CYCLE_AT, LAST_CYCLE_SECONDS, STATE_CACHE
    while True:
        cycle_started = time.monotonic()
        accounts = read_json(ACCOUNTS_FILE, {})
        with STATE_CONDITION:
            previous_states = dict(STATE_CACHE)

        results = {}
        if accounts:
            with ThreadPoolExecutor(max_workers=min(8, len(accounts)), thread_name_prefix="punch-poll") as pool:
                futures = {
                    pool.submit(inspect_account, account_id, account, previous_states.get(account_id, {})): account_id
                    for account_id, account in accounts.items()
                }
                for future in as_completed(futures):
                    account_id = futures[future]
                    try:
                        results[account_id] = future.result()
                    except Exception as exc:
                        state = dict(previous_states.get(account_id, {}))
                        state.update({"account_id": account_id, "error": type(exc).__name__, "checked_at": datetime.now().isoformat()})
                        results[account_id] = state

        changed = any(
            comparable_state(previous_states.get(account_id)) != comparable_state(state)
            for account_id, state in results.items()
        ) or set(previous_states) != set(results)
        with STATE_CONDITION:
            STATE_CACHE = results
            if changed:
                write_json(STATE_FILE, results)
                STATE_CONDITION.notify_all()

        LAST_CYCLE_AT = time.time()
        LAST_CYCLE_SECONDS = time.monotonic() - cycle_started
        time.sleep(max(0.05, POLL_SECONDS - LAST_CYCLE_SECONDS))


def wait_for_state_change(account_id: str, last_signature: str, last_missing: bool, timeout: int) -> Tuple[Optional[dict], bool]:
    deadline = time.monotonic() + max(1, min(timeout, LONG_POLL_TIMEOUT))
    with STATE_CONDITION:
        while True:
            state = STATE_CACHE.get(account_id)
            if state is None:
                return None, False
            if state.get("last_punch_signature", "") != last_signature or bool(state.get("missing_punch")) != last_missing:
                return dict(state), False
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return dict(state), True
            STATE_CONDITION.wait(remaining)


def token_from_request(handler, query: dict) -> str:
    authorization = handler.headers.get("Authorization", "")
    if authorization.startswith("Bearer "):
        return authorization[7:].strip()
    return ""


def validate_device_token(candidate: str) -> bool:
    if not candidate:
        return False
    devices = read_json(PAIRED_DEVICES_FILE, {})
    return any(secrets.compare_digest(str(device.get("device_token", "")), candidate) for device in devices.values())


def pair_rate_limited(client_ip: str) -> bool:
    now = time.monotonic()
    with PAIR_ATTEMPT_LOCK:
        attempts = [stamp for stamp in PAIR_ATTEMPTS.get(client_ip, []) if now - stamp < 60]
        attempts.append(now)
        PAIR_ATTEMPTS[client_ip] = attempts
        return len(attempts) > 5


def safe_text(value, maximum: int) -> str:
    text = str(value or "").strip()
    if not text or len(text) > maximum or any(ord(char) < 32 for char in text):
        raise ValueError("invalid_field")
    return text


def validate_https_url(value, field: str) -> str:
    text = safe_text(value, 500).rstrip("/")
    parsed = urlparse(text)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("invalid_" + field)
    return text


class SecureThreadingHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "CardinalPunch/2.0"

    def log_message(self, fmt, *args):
        return

    def _send_json(self, payload: dict, status=200):
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.end_headers()
        self.wfile.write(raw)

    def _read_json(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise ValueError("invalid_length")
        if length < 0 or length > MAX_BODY_BYTES:
            raise ValueError("payload_too_large")
        raw = self.rfile.read(length) if length else b"{}"
        payload = json.loads(raw.decode("utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("invalid_json")
        return payload

    def _authorized(self, query: dict) -> bool:
        return validate_device_token(token_from_request(self, query))

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if parsed.path == "/health":
            lag = None if not LAST_CYCLE_AT else round(time.time() - LAST_CYCLE_AT, 3)
            self._send_json({
                "ok": bool(LAST_CYCLE_AT and lag < max(15, POLL_SECONDS * 5)),
                "poll_seconds": POLL_SECONDS,
                "cycle_seconds": round(LAST_CYCLE_SECONDS, 3),
                "last_cycle_lag_seconds": lag,
                "accounts": len(STATE_CACHE),
            })
            return
        if parsed.path == "/api/state":
            if not self._authorized(query):
                self._send_json({"ok": False, "error": "unauthorized"}, 401)
                return
            account_id = query.get("account_id", [""])[0]
            with STATE_CONDITION:
                state = STATE_CACHE.get(account_id)
            if state is None:
                self._send_json({"ok": False, "error": "unknown_account"}, 404)
                return
            self._send_json(dict({"ok": True}, **state))
            return
        self._send_json({"ok": False, "error": "not_found"}, 404)

    def do_POST(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        try:
            payload = self._read_json()
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            status = 413 if str(exc) == "payload_too_large" else 400
            self._send_json({"ok": False, "error": str(exc) or "invalid_request"}, status)
            return

        if parsed.path == "/api/pair":
            if pair_rate_limited(self.client_address[0]):
                self._send_json({"ok": False, "error": "too_many_attempts"}, 429)
                return
            supplied = str(payload.get("pair_code", ""))
            if not secrets.compare_digest(supplied, PAIR_CODE):
                self._send_json({"ok": False, "error": "invalid_pair_code"}, 401)
                return
            try:
                device_id = safe_text(payload.get("device_id"), 200)
                device_name = safe_text(payload.get("device_name") or "Android", 200)
            except ValueError as exc:
                self._send_json({"ok": False, "error": str(exc)}, 400)
                return
            devices = read_json(PAIRED_DEVICES_FILE, {})
            existing = devices.get(device_id, {})
            device_token = existing.get("device_token") or secrets.token_urlsafe(32)
            devices[device_id] = {
                "device_id": device_id,
                "device_name": device_name,
                "device_token": device_token,
                "paired_at": existing.get("paired_at") or datetime.now().isoformat(),
            }
            write_json(PAIRED_DEVICES_FILE, devices)
            self._send_json({"ok": True, "device_token": device_token, "device_id": device_id})
            return

        if not self._authorized(query):
            self._send_json({"ok": False, "error": "unauthorized"}, 401)
            return

        if parsed.path == "/api/wait_state":
            try:
                account_id = safe_text(payload.get("account_id"), 200)
                timeout = int(payload.get("timeout", LONG_POLL_TIMEOUT))
            except (ValueError, TypeError) as exc:
                self._send_json({"ok": False, "error": "invalid_request"}, 400)
                return
            state, timed_out = wait_for_state_change(
                account_id,
                str(payload.get("last_signature", "")),
                bool(payload.get("last_missing", False)),
                timeout,
            )
            if state is None:
                self._send_json({"ok": False, "error": "unknown_account"}, 404)
                return
            self._send_json(dict({"ok": True, "timeout": timed_out}, **state))
            return

        if parsed.path == "/api/register_account":
            try:
                company_code = safe_text(payload.get("company_code"), 100)
                nip = safe_text(payload.get("nip"), 100)
                account = {
                    "account_id": company_code + ":" + nip,
                    "company_code": company_code,
                    "nip": nip,
                    "api_url": validate_https_url(payload.get("api_url"), "api_url"),
                    "site_path": validate_https_url(payload.get("site_path"), "site_path"),
                    "user_id": safe_text(payload.get("user_id"), 200),
                    "hours_link": str(payload.get("hours_link", ""))[:500],
                    "full_name": str(payload.get("full_name", ""))[:200],
                }
            except ValueError as exc:
                self._send_json({"ok": False, "error": str(exc)}, 400)
                return
            accounts = read_json(ACCOUNTS_FILE, {})
            accounts[account["account_id"]] = account
            write_json(ACCOUNTS_FILE, accounts)
            self._send_json({"ok": True, "account_id": account["account_id"]})
            return

        self._send_json({"ok": False, "error": "not_found"}, 404)


if __name__ == "__main__":
    for data_file in (ACCOUNTS_FILE, STATE_FILE, PAIRED_DEVICES_FILE):
        initialize_json_file(data_file)
    with STATE_CONDITION:
        STATE_CACHE = read_json(STATE_FILE, {})
    PAIR_CODE = resolve_pair_code()
    threading.Thread(target=monitor_loop, name="punch-monitor", daemon=True).start()
    server = SecureThreadingHTTPServer((HOST, PORT), Handler)
    public_url = PUBLIC_BASE_URL.strip() or "http://{}:{}".format(HOST, PORT)
    print("Cardinal Punch watch server running on {}:{}".format(HOST, PORT))
    print("Pair secret file: {}".format(PAIR_CODE_FILE))
    print("Android URL to use: {}".format(public_url))
    server.serve_forever()
