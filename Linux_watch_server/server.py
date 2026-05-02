import json
import os
import secrets
import random
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import requests
from bs4 import BeautifulSoup


ROOT = Path(__file__).resolve().parent
ACCOUNTS_FILE = ROOT / "accounts.json"
STATE_FILE = ROOT / "state.json"
PAIR_CODE_FILE = ROOT / "pair_code.txt"
PAIRED_DEVICES_FILE = ROOT / "paired_devices.json"
PORT = int(os.getenv("PORT", "39014"))
HOST = os.getenv("HOST", "0.0.0.0")
POLL_SECONDS = int(os.getenv("POLL_SECONDS", "1"))
LONG_POLL_TIMEOUT = int(os.getenv("LONG_POLL_TIMEOUT", "45"))
MAX_PENDING_OUT_HOURS = 16
PUBLIC_BASE_URL = os.getenv("PUBLIC_BASE_URL", "")
PAIR_CODE = ""
STATE_CONDITION = threading.Condition()
STATE_REVISION = 0


def read_json(path: Path, default):
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return default


def write_json(path: Path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def parse_bool(value: str) -> bool:
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


def comparable_state(state: dict) -> dict:
    if not isinstance(state, dict):
        return {}
    payload = dict(state)
    payload.pop("checked_at", None)
    return payload


def update_state_if_changed(states: dict, account_id: str, new_state: dict) -> bool:
    if comparable_state(states.get(account_id)) == comparable_state(new_state):
        return False
    states[account_id] = new_state
    return True


def wait_for_state_change(account_id: str, last_signature: str, last_missing: bool, timeout_seconds: int) -> tuple[dict | None, bool]:
    timeout_seconds = max(1, min(timeout_seconds, LONG_POLL_TIMEOUT))
    deadline = time.time() + timeout_seconds

    with STATE_CONDITION:
        observed_revision = STATE_REVISION
        while True:
            states = read_json(STATE_FILE, {})
            state = states.get(account_id)
            if state is None:
                return None, False

            signature = state.get("last_punch_signature", "")
            missing = bool(state.get("missing_punch", False))
            changed = signature != last_signature or missing != last_missing
            if changed:
                return state, False

            remaining = deadline - time.time()
            if remaining <= 0:
                return state, True

            STATE_CONDITION.wait(timeout=min(remaining, 5))
            observed_revision = STATE_REVISION


def resolve_pair_code() -> str:
    env_code = os.getenv("CARDINAL_PUNCH_PAIR_CODE", "").strip()
    if env_code:
        PAIR_CODE_FILE.write_text(env_code, encoding="utf-8")
        return env_code

    if PAIR_CODE_FILE.exists():
        code = PAIR_CODE_FILE.read_text(encoding="utf-8").strip()
        if code:
            return code

    code = f"{random.randint(0, 999999):06d}"
    PAIR_CODE_FILE.write_text(code, encoding="utf-8")
    return code


def read_paired_devices() -> dict:
    return read_json(PAIRED_DEVICES_FILE, {})


def write_paired_devices(devices: dict):
    write_json(PAIRED_DEVICES_FILE, devices)


def validate_device_token(device_token: str) -> bool:
    if not device_token:
        return False
    devices = read_paired_devices()
    for _, device in devices.items():
        if device.get("device_token") == device_token:
            return True
    return False


def fetch_oauth_token(api_url: str, company_code: str) -> str:
    response = requests.post(
        f"{api_url.rstrip('/')}/oauth/token",
        data={"username": company_code, "password": company_code, "grant_type": "password"},
        timeout=30,
    )
    response.raise_for_status()
    return response.json()["access_token"]


def fetch_user(api_url: str, token: str, nip: str) -> dict:
    response = requests.get(
        f"{api_url.rstrip('/')}/Parameters/User",
        params={"NIP": nip},
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
        timeout=30,
    )
    response.raise_for_status()
    return response.json()


def fetch_hours(account: dict) -> tuple[str, list[str], str, str]:
    session = requests.Session()
    url = (
        f"{account['site_path'].rstrip('/')}/consult-mobile.asp?QLangue=fr"
        f"&pinOverride={account['nip']}&noEmployeOverride={account['user_id']}"
    )
    html = session.get(url, timeout=30).text
    soup = BeautifulSoup(html, "html.parser")
    bank_hours = ""
    bank = soup.select_one(".current-week .mal b")
    if bank:
        bank_hours = bank.get_text(" ", strip=True)

    today_label = datetime.now().strftime("%d").lstrip("0")
    target_cell = None
    day_cells = soup.select("tbody.role-body.mes-heures tr.extra-row td.calendar_day")
    for cell in day_cells:
        label = cell.select_one("p.day")
        if label and label.get_text(strip=True).lstrip("0") == today_label:
            target_cell = cell
            break

    shifts = []
    regular = "00:00"
    overtime = "00:00"
    if target_cell is not None:
        for span in target_cell.select(".time-cell.temps span"):
            shifts.append(span.get_text(" ", strip=True))
        regular_span = target_cell.select_one(".time-cell.temps-reg span")
        overtime_span = target_cell.select_one(".time-cell.temps-supp span")
        if regular_span:
            regular = regular_span.get_text(" ", strip=True)
        if overtime_span:
            overtime = overtime_span.get_text(" ", strip=True)

    return bank_hours, shifts, regular, overtime


def infer_punch_kind(check_time: str, shifts: list[str]) -> str:
    if not check_time:
        return "unknown"
    hhmm = check_time[11:16]
    latest_kind = "unknown"
    for shift in shifts:
        parts = [part.strip() for part in shift.split("-")]
        start = parts[0] if parts else ""
        end = parts[1] if len(parts) > 1 else ""
        if start:
            latest_kind = "entry"
            if hhmm == start[:5]:
                return "entry" if not end else latest_kind
        if end:
            latest_kind = "exit"
            if hhmm == end[:5]:
                return "exit"
    return latest_kind


def monitor_loop():
    global STATE_REVISION
    while True:
        try:
            accounts = read_json(ACCOUNTS_FILE, {})
            states = read_json(STATE_FILE, {})
            changed = False

            removed_accounts = [account_id for account_id in list(states.keys()) if account_id not in accounts]
            for account_id in removed_accounts:
                del states[account_id]
                changed = True

            for account_id, account in accounts.items():
                try:
                    token = fetch_oauth_token(account["api_url"], account["company_code"])
                    user = fetch_user(account["api_url"], token, account["nip"])
                    last_punch = user.get("LastPunch") or {}
                    check_time = last_punch.get("CheckTime") or ""
                    signature = f"{check_time}|{last_punch.get('CheckType','')}|{last_punch.get('Note','')}"
                    bank_hours, shifts, regular_hours, overtime_hours = fetch_hours(account)
                    punch_kind = infer_punch_kind(check_time, shifts)

                    previous = states.get(account_id, {})
                    pending_out_since = previous.get("pending_out_since", 0)
                    if signature and signature != previous.get("last_punch_signature", ""):
                        if punch_kind == "entry":
                            try:
                                pending_out_since = int(datetime.strptime(check_time, "%Y-%m-%d %H:%M:%S").timestamp())
                            except Exception:
                                pending_out_since = int(time.time())
                        elif punch_kind == "exit":
                            pending_out_since = 0

                    missing_punch = False
                    if pending_out_since:
                        missing_punch = time.time() - pending_out_since >= MAX_PENDING_OUT_HOURS * 3600

                    changed = update_state_if_changed(states, account_id, {
                        "account_id": account_id,
                        "full_name": user.get("Name", account.get("full_name", "")),
                        "last_punch_signature": signature,
                        "last_punch_time": check_time,
                        "last_punch_kind": punch_kind,
                        "bank_hours": bank_hours,
                        "today": datetime.now().strftime("%Y-%m-%d"),
                        "today_shifts": shifts,
                        "regular_hours": regular_hours,
                        "overtime_hours": overtime_hours,
                        "pending_out_since": pending_out_since,
                        "missing_punch": missing_punch,
                        "checked_at": datetime.now().isoformat(),
                    }) or changed
                except Exception as exc:
                    changed = update_state_if_changed(states, account_id, {
                        "account_id": account_id,
                        "error": str(exc),
                        "checked_at": datetime.now().isoformat(),
                    }) or changed

            if changed:
                write_json(STATE_FILE, states)
                with STATE_CONDITION:
                    STATE_REVISION += 1
                    STATE_CONDITION.notify_all()
        except Exception:
            pass

        time.sleep(POLL_SECONDS)


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, payload: dict, status: int = 200):
        raw = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _require_device(self, query: dict) -> bool:
        device_token = query.get("device_token", [""])[0]
        return validate_device_token(device_token)

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)

        if parsed.path == "/health":
            self._send_json({"ok": True, "poll_seconds": POLL_SECONDS})
            return

        if parsed.path == "/api/state":
            if not self._require_device(query):
                self._send_json({"ok": False, "error": "unauthorized"}, 401)
                return
            account_id = query.get("account_id", [""])[0]
            states = read_json(STATE_FILE, {})
            if account_id not in states:
                self._send_json({"ok": False, "error": "unknown_account"}, 404)
                return
            payload = {"ok": True}
            payload.update(states[account_id])
            self._send_json(payload)
            return

        if parsed.path == "/api/wait_state":
            if not self._require_device(query):
                self._send_json({"ok": False, "error": "unauthorized"}, 401)
                return
            account_id = query.get("account_id", [""])[0]
            last_signature = query.get("last_signature", [""])[0]
            last_missing = parse_bool(query.get("last_missing", ["false"])[0])
            timeout_seconds = int(query.get("timeout", [str(LONG_POLL_TIMEOUT)])[0] or LONG_POLL_TIMEOUT)

            state, timeout_reached = wait_for_state_change(account_id, last_signature, last_missing, timeout_seconds)
            if state is None:
                self._send_json({"ok": False, "error": "unknown_account"}, 404)
                return

            payload = {"ok": True, "timeout": timeout_reached}
            payload.update(state)
            self._send_json(payload)
            return

        self._send_json({"ok": False, "error": "not_found"}, 404)

    def do_POST(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8") if length else "{}"
        payload = json.loads(body or "{}")

        if parsed.path == "/api/pair":
            pair_code = str(payload.get("pair_code", "")).strip()
            device_id = str(payload.get("device_id", "")).strip()
            device_name = str(payload.get("device_name", "")).strip()
            if pair_code != PAIR_CODE:
                self._send_json({"ok": False, "error": "invalid_pair_code"}, 401)
                return
            if not device_id:
                self._send_json({"ok": False, "error": "missing_device_id"}, 400)
                return

            devices = read_paired_devices()
            existing = devices.get(device_id, {})
            device_token = existing.get("device_token") or secrets.token_urlsafe(32)
            devices[device_id] = {
                "device_id": device_id,
                "device_name": device_name,
                "device_token": device_token,
                "paired_at": existing.get("paired_at") or datetime.now().isoformat(),
                "last_seen": datetime.now().isoformat(),
            }
            write_paired_devices(devices)
            self._send_json({"ok": True, "device_token": device_token, "device_id": device_id})
            return

        if parsed.path != "/api/register_account":
            self._send_json({"ok": False, "error": "not_found"}, 404)
            return
        if not self._require_device(query):
            self._send_json({"ok": False, "error": "unauthorized"}, 401)
            return

        required = ["account_id", "company_code", "nip", "api_url", "site_path", "user_id"]
        # Android may not send user_id yet on first sync; fallback to derived value if full name is present later.
        if "user_id" not in payload or not payload["user_id"]:
            payload["user_id"] = payload.get("account_id", "").replace(":", "--")

        accounts = read_json(ACCOUNTS_FILE, {})
        accounts[payload["account_id"]] = payload
        write_json(ACCOUNTS_FILE, accounts)
        self._send_json({"ok": True, "account_id": payload["account_id"]})


if __name__ == "__main__":
    PAIR_CODE = resolve_pair_code()
    ACCOUNTS_FILE.touch(exist_ok=True)
    if ACCOUNTS_FILE.read_text(encoding="utf-8").strip() == "":
        write_json(ACCOUNTS_FILE, {})
    STATE_FILE.touch(exist_ok=True)
    if STATE_FILE.read_text(encoding="utf-8").strip() == "":
        write_json(STATE_FILE, {})
    PAIRED_DEVICES_FILE.touch(exist_ok=True)
    if PAIRED_DEVICES_FILE.read_text(encoding="utf-8").strip() == "":
        write_json(PAIRED_DEVICES_FILE, {})

    thread = threading.Thread(target=monitor_loop, daemon=True)
    thread.start()

    server = ThreadingHTTPServer((HOST, PORT), Handler)
    public_url = PUBLIC_BASE_URL.strip() or f"http://127.0.0.1:{PORT}"
    print(f"Cardinal Punch watch server running on {HOST}:{PORT}")
    print(f"Pair code: {PAIR_CODE}")
    print(f"Pair code file: {PAIR_CODE_FILE}")
    print(f"Android URL to use: {public_url}")
    server.serve_forever()
