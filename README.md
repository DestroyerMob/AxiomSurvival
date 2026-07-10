# Axiom Survival

Axiom Survival is a Fabric 1.21.1 add-on that puts Axiom block edits behind survival material, tool, and permission checks. It is designed to load directly with Axiom on Fabric; Minecraft Beyond runs it in a NeoForge instance through Sinytra Connector.

## Current Status

Experimental internal playtesting. The survival boundary is disabled by default and must be enabled before launch because its Axiom capture and permission mixins are selected during startup.

## Project Facts

- Mod id: `axiomsurvival`
- Current version: `0.1.0`
- Target: Minecraft 1.21.1, Fabric Loader 0.16.14, Java 21
- Required mod: Axiom
- Minecraft Beyond runtime: NeoForge through Sinytra Connector
- Config file: `config/axiom-survival.json`

## How It Works

When capture is enabled, the add-on intercepts Axiom set-block and block-buffer operations before Axiom changes the world. It builds the complete requested block set, validates it, and computes survival costs first. Failed validation rejects the operation without charging the player; a valid operation consumes its costs and applies the captured blocks.

For survival players, validation includes:

- A material cost for every placed block that has a survival item form.
- Credit for compatible source blocks removed by the same move, so moving material does not charge twice.
- Filled-bucket costs for water, lava, and powder snow, with empty buckets returned after payment.
- Suitable-tool checks for source blocks that require the correct tool.
- Tool durability accounting across all removals in the edit.
- Rejection of unbreakable blocks and blocks without a survival item representation.
- A configurable maximum captured-edit size.

Creative players bypass material and tool costs, but the edit still passes through the capture path. Biome-buffer edits are rejected while capture mode is active.

The add-on grants survival players a restricted Axiom permission profile for builder operations without granting entity, world, gamemode, or teleport powers. It also lets ordinary block use/attack interactions pass through when the Axiom editor and builder-tool slot are not active.

## Configuration

The config is generated at `config/axiom-survival.json`:

```json
{
  "enableAxiomVanillaEditCapture": false,
  "axiomVanillaEditMaxPendingBlocks": 100000
}
```

- `enableAxiomVanillaEditCapture` enables the startup-gated Axiom mixins and survival checks.
- `axiomVanillaEditMaxPendingBlocks` limits one captured operation to between 1 and 1,000,000 changed positions.

Restart the game after changing the capture flag. There are no `/vanillaedit` staging commands in the current implementation; validated edits are handled directly when Axiom submits them.

## Building

```sh
./gradlew build
```

The built jar is written to `build/libs/`.

## Minecraft Beyond Integration

Minecraft Beyond manages the Fabric jar as a client-side local mod and supplies Axiom, Sinytra Connector, Forgified Fabric API, and the runtime config. Mod Quality Picker can disable Axiom and Axiom Survival together in profiles that do not need builder tooling.

## Known Limitations

- The integration reflects into Axiom internals for block buffers and interaction state, so an Axiom update can require a compatibility pass.
- Only block-state edits are supported. Biome edits and broader world/entity operations remain blocked while capture is enabled.
- Blocks without an item form are rejected, even when another survival mechanic could theoretically create them.
- This is a cost-and-permission boundary, not a general-purpose undo or queued-edit system.

## License

All rights reserved.
