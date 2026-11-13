<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHGiveaway

*Giveaways planifiés : participation automatique à la connexion et tirage à date fixe.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Giveaways planifiés : participation automatique à la connexion et tirage à date fixe.

Utilisé sur : **Opale**.

## Dépendances

- Obligatoires : `Vault`
- Optionnelle : `SHLogs`
- Optionnelle : `SHNetwork` (plusieurs serveurs de jeu, voir plus bas)

## Commandes

Racine d'administration `/shgiveaway` (aucun alias). Chaque sous-commande a sa permission `shgiveaway.admin.<sous-commande>` ; `shgiveaway.admin` les donne toutes. Elles marchent aussi depuis la console.

| Commande | Description | Permission |
|---|---|---|
| `/shgiveaway reload` | Recharge `config.yml` et `messages.yml` (le bloc `reseau` demande un redémarrage) ; un fichier invalide annule le rechargement ; `data.yml` n'est pas relu | `shgiveaway.admin.reload` |
| `/shgiveaway draw <id>` | Tire au sort le giveaway tout de suite | `shgiveaway.admin.draw` |
| `/shgiveaway status <id>` | État d'un giveaway | `shgiveaway.admin.status` |
| `/shgiveaway active` | Giveaways actifs | `shgiveaway.admin.active` |
| `/shgiveaway history [page]` | Derniers tirages | `shgiveaway.admin.history` |
| `/shgiveaway participants <id> [page]` | Participants | `shgiveaway.admin.participants` |
| `/shgiveaway add <id> <joueur\|uuid>` | Inscrit un joueur | `shgiveaway.admin.add` |
| `/shgiveaway remove <id> <joueur\|uuid>` | Désinscrit un joueur | `shgiveaway.admin.remove` |
| `/shgiveaway blacklist add\|remove <id> <joueur\|uuid>`, `/shgiveaway blacklist list <id> [page]` | Exclut un joueur, le réintègre, liste les exclus | `shgiveaway.admin.blacklist` |
| `/shgiveaway import` | Reprend `data.yml` dans la base partagée (mode réseau) | `shgiveaway.admin.import` |
| `/shgiveaway pending [page]` | Récompenses de gagnants pas encore données (joueur, giveaway, nombre) | `shgiveaway.admin.pending` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shgiveaway.admin` | Toutes les commandes d'administration (parent des suivantes) | `op` |
| `shgiveaway.admin.reload`, `.draw`, `.status`, `.active`, `.history`, `.participants`, `.add`, `.remove`, `.blacklist`, `.import`, `.pending` | Une permission par sous-commande | `false` |

## Configuration

Fichiers par défaut, avec des valeurs d'exemple. Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHGiveaway/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

Au premier démarrage avec `messages.yml`, le fichier est créé à partir de la section `messages` de l'ancien `config.yml` (textes déjà en place), complétée par ceux du jar ; cette section n'est plus lue ensuite.

Chaque gagnant (tirage et remise de la récompense à sa connexion) est écrit dans le fichier `gagnants` du dossier `SHGiveaway` de SHLogs (`plugins/SHLogs/`). Sans SHLogs, les lignes passent dans la console. `data.yml` garde l'état des giveaways et l'historique affiché par `/shgiveaway history`. Un giveaway se rejoue avec un nouveau tour : `round-key` (par défaut la valeur de `draw-at`) remet participants et état « tiré » à zéro quand il change.

## Plusieurs serveurs de jeu

Sans SHNetwork, le plugin tourne comme avant : tout est dans `data.yml`, chaque serveur tire ses giveaways.

Avec SHNetwork (et `reseau.actif: true`, par défaut), participants, gagnants, joueurs exclus et récompenses en attente sont dans la base MariaDB du bloc `reseau.base-de-donnees` (tables `giveaway_rounds`, `giveaway_players`, `giveaway_pending`). Tous les serveurs doivent avoir les mêmes giveaways dans `config.yml`.

- **Participation** : un joueur est inscrit en se connectant sur n'importe quel serveur. Le message de participation n'est envoyé qu'une fois, où que le joueur se connecte.
- **Tirage** : seul le serveur meneur (tâche `shgiveaway` de SHNetwork) lance les tirages planifiés, et un autre prend la main s'il tombe. Le tirage lui-même est une transaction : le serveur qui passe le giveaway de « à tirer » à « tiré » est le seul à choisir les gagnants, un `/shgiveaway draw` lancé sur un autre serveur au même moment ne tire pas une seconde fois. Tous les serveurs annoncent le résultat.
- **Récompenses** : les récompenses des gagnants sont écrites en base avec le tirage. Un gagnant connecté sur un serveur les reçoit tout de suite, les autres à leur prochaine connexion sur n'importe quel serveur. Chaque ligne est réclamée par un `DELETE`, donc donnée une seule fois.
- **Historique** : `/shgiveaway history` reste local à chaque serveur, alimenté par l'annonce de chaque tirage.
- **Administration** : `add`, `remove` et `blacklist` retrouvent un joueur par UUID, parmi les joueurs connectés sur le réseau, dans la table `reseau.table-comptes` (comptes connus du réseau), sinon parmi ceux déjà venus sur le serveur.

`/shgiveaway import` reprend dans la base le contenu de `data.yml` d'avant le mode réseau (participants, gagnants, joueurs exclus, tirages faits, récompenses en attente). À lancer avant le premier tirage en mode réseau ; elle peut être relancée sans rien compter deux fois. Les giveaways déjà tirés dans `data.yml` sont repris comme tirés au premier démarrage.

Le bloc `reseau` demande un redémarrage.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
