# Linux Watch Server

Serveur Python pour surveiller rapidement Info-Punch et exposer un etat simple a l'application Android.

## Usage

1. Installer les dependances:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

2. Cle de pairage:

```bash
export CARDINAL_PUNCH_PAIR_CODE="une-cle-longue-et-aleatoire"
```

Optionnel. Si tu ne definis rien, le serveur genere une cle forte dans `pair_code.txt` avec des permissions limitees au proprietaire.

3. Lancer le serveur:

```bash
python3 server.py
```

Par securite, le serveur ecoute par defaut sur `127.0.0.1:39049`. Il faut le publier derriere un proxy HTTPS.

Au lancement, le serveur affiche:

- le code de pairage a 6 chiffres a recopier une seule fois dans l'app Android
- le chemin du fichier `pair_code.txt`
- l'URL Android a utiliser

Une fois l'appareil Android associe, le serveur lui attribue un jeton interne persistant. Tu n'as plus besoin de re-pairer a chaque lancement.

## Port conseille

- Interne: `39049`
- Production: Nginx/Caddy en HTTPS sur `443`, puis relais vers `127.0.0.1:39049`

Le client Android refuse maintenant un relais HTTP non chiffre. Les jetons d'appareil sont envoyes dans l'en-tete `Authorization: Bearer ...`, jamais dans l'URL.

## API

- `GET /health`
- `POST /api/pair`
- `POST /api/register_account`
- `POST /api/wait_state`
- `GET /api/state?account_id=...`

Les trois routes protegees demandent l'en-tete `Authorization: Bearer <device_token>`.

Le relais reutilise les jetons OAuth, interroge les comptes en parallele et separe la lecture rapide du dernier poincon de la page d'heures plus lourde. `POLL_SECONDS` accepte les decimales et vaut `0.75` par defaut.

L'application Android peut enregistrer un compte depuis l'ecran Parametres puis activer le mode ultra-rapide.
