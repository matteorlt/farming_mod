# Farming Profit

Mod Fabric **client-only** pour Minecraft **26.1.2** (Hypixel SkyBlock Garden).

- HUD coins/heure (prix Bazaar Cofl)
- Hitbox crops mature = 1 bloc
- Visitor's Logbook : unique served
- Loadout Pest / Farm via canne à pêche
- Vente NPC (`/fprofit sell`)

## Installation

1. Fabric Loader + Fabric API pour 26.1.2
2. Copie `farmingprofit-x.y.z.jar` dans `.minecraft/mods/`

Releases : https://github.com/matteorlt/farming_mod/releases

## Commandes

Voir [`COMMANDS.md`](COMMANDS.md). Toutes commencent par `/fprofit`.

## Build

Java 25 requis.

```
./gradlew.bat build
```

JAR : `build/libs/farmingprofit-1.0.0.jar`

## Mise à jour

Au login, le mod compare sa version à la dernière release GitHub et affiche un lien dans le chat si une update existe.

Un JAR déjà chargé par Fabric **ne peut pas** être remplacé à chaud : il faut fermer Minecraft, mettre le nouveau JAR dans `mods/`, et relancer. Relancer le launcher tout seul depuis le jeu n’est pas fiable (Prism, official launcher, etc.).
