# Axiom Survival

Axiom Survival is a Fabric 1.21.1 add-on for Axiom that stages Axiom block edits behind survival inventory costs.

It is designed to run directly with Axiom on Fabric. In Minecraft Beyond's NeoForge instance, it loads alongside Axiom through Sinytra Connector.

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
- Capture mixins and commands are only applied when `enableAxiomVanillaEditCapture` is set to `true` before launch.
