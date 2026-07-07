# Axiom Survival

Axiom Survival is a Fabric 1.21.1 add-on for Axiom that stages Axiom block edits behind survival inventory costs.

It is designed to run directly with Axiom on Fabric. In Minecraft Beyond's NeoForge instance, it loads alongside Axiom through Sinytra Connector.

## Project Facts

- Mod id: `axiomsurvival`
- Current version: `0.1.0`
- Target: Minecraft 1.21.1, Fabric Loader 0.16.14, Java 21
- Required mod: Axiom
- Minecraft Beyond runtime: NeoForge through Sinytra Connector
- Config file: `config/axiom-survival.json`

## Player Commands

- `/vanillaedit status` shows the staged edit and material cost.
- `/vanillaedit apply` consumes materials and applies the staged edit.
- `/vanillaedit cancel` discards the staged edit.
- `/axiomsurvival` provides the same subcommands as an explicit alias.

## Config

The config file is `config/axiom-survival.json`.

```json
{
  "enableAxiomVanillaEditCapture": false,
  "axiomVanillaEditMaxPendingBlocks": 100000
}
```

## Notes

- Creative players can apply staged edits without material cost.
- Water, lava, and powder snow cost filled buckets and return empty buckets.
- Blocks without a survival item form are rejected instead of being applied for free.
- Biome edits are blocked while vanilla edit capture is enabled.
- Survival players receive a restricted Axiom permission profile while capture is enabled so builder tools can be used without granting entity, world, gamemode, or teleport powers.
- Capture mixins and commands are only applied when `enableAxiomVanillaEditCapture` is set to `true` before launch.

## Building

```sh
./gradlew build
```

The built jar is written to `build/libs/`.

## License

All rights reserved.
