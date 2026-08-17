# Méthodes d'envoi de note Info-Punch

Dernière validation utilisateur : les méthodes 1 et 7 ont réellement créé une note.

## Méthode active : 1

L'application utilise uniquement cette requête :

`GET ajaxCommands/UserPanel.AddNotesEmp.asp?DateJour=dd-MM-yyyy&QRaison=<note>`

Elle est exécutée après l'ouverture de la session du portail et avec le référent du portail. Aucune autre variante de date, de clé ou de méthode HTTP n'est essayée automatiquement.

## Solution de repli conservée : 7

À conserver uniquement si la méthode active cesse de fonctionner :

`GET ajaxCommands/UserPanel.AddNotesEmp.asp?dateJour=dd-MM-yyyy&qRaison=<note>`

Cette méthode n'est pas dans le code actif et ne doit pas être exécutée automatiquement, afin d'éviter l'envoi de plusieurs notes.
