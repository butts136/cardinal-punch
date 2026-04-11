# Linux Watch Server

Serveur Python pour surveiller rapidement Info-Punch et exposer un etat simple a l'application Android.

## Usage

1. Installer les dependances:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

2. Code de pairage:

```bash
export CARDINAL_PUNCH_PAIR_CODE="123456"
```

Optionnel. Si tu ne definis rien, le serveur va generer automatiquement un code a 6 chiffres, l'enregistrer dans `pair_code.txt` et l'afficher au demarrage.

3. Lancer le serveur:

```bash
python3 server.py
```

Par defaut, le serveur ecoute sur `0.0.0.0:39014`.

Au lancement, le serveur affiche:

- le code de pairage a 6 chiffres a recopier une seule fois dans l'app Android
- le chemin du fichier `pair_code.txt`
- l'URL Android a utiliser

Une fois l'appareil Android associe, le serveur lui attribue un jeton interne persistant. Tu n'as plus besoin de re-pairer a chaque lancement.

## Port conseille

- En direct: `39014`
- Recommande en production: mettre Nginx/Caddy devant et publier en `443`, puis laisser ce script ecouter en interne sur `39014`

Concretement:

- si tu accedes directement au script Python depuis Internet, utilise `http://46.232.211.10:39014`
- si tu mets un reverse proxy HTTPS devant, utilise plutot `https://46.232.211.10` ou ton nom de domaine en `443`

## API

- `GET /health`
- `POST /api/pair`
- `POST /api/register_account?device_token=...`
- `GET /api/state?device_token=...&account_id=...`

L'application Android peut enregistrer un compte depuis l'ecran Parametres puis activer le mode ultra-rapide.
