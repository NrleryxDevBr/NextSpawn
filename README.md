# NextSpawn 🧭

NextSpawn is a lightweight, beautiful spawn system designed for Paper/Folia servers.
Light beautiful spawn system compatible with Folia.

## Features

- Folia compatible (RegionScheduler / GlobalRegionScheduler)
- Actionbar teleport countdown (5 → 1)
- Cancel teleport on movement (configurable)
- Configurable sounds & particles (countdown + success/fail/cancel)
- Void rescue (DamageCause.VOID) with cooldown
- Optimized for high player counts (cached messages/settings)

## Commands & Permissions

| Command | Description | Permission |
|---|---|---|
| `/spawn` | Teleport to spawn | `nextspawn.use` |
| `/setspawn` | Set spawn | `nextspawn.admin` |
| `/nextspawn reload` | Reload config | `nextspawn.admin` |

## Configuration

`config.yml`

```yml
settings:
  teleport-delay-seconds: 5
  cancel-on-move: true
  cancel-move-distance: 0.1

  countdown-sound: BLOCK_NOTE_BLOCK_PLING
  countdown-particle: PORTAL
  countdown-particle-count: 12

  teleport-sound: ENTITY_ENDERMAN_TELEPORT
  teleport-sound-volume: 0.6
  teleport-sound-pitch: 1.0
  teleport-success-particle: PORTAL
  teleport-success-particle-count: 30

  teleport-failed-sound: ENTITY_VILLAGER_NO
  teleport-cancelled-sound: BLOCK_NOTE_BLOCK_BASS

  spawn-set-sound: BLOCK_NOTE_BLOCK_CHIME
  spawn-not-set-sound: BLOCK_NOTE_BLOCK_BASS

  reload-sound: BLOCK_NOTE_BLOCK_BIT

  void-rescue:
    enabled: true
    cooldown-seconds: 10

messages:
  teleport:
    count: "&7ᴛᴇʟᴇᴘᴏʀᴛɪɴɢ: &5{countdown} &7sᴇᴄᴏɴᴅs"
    success: "&7sᴜᴄᴄᴇssғᴜʟʟʏ ᴛᴇʟᴇᴘᴏʀᴛᴇᴅ ᴛᴏ &5sᴘᴀᴡɴ&7"
    failed: "&cᴛᴇʟᴇᴘᴏʀᴛ ғᴀɪʟᴇᴅ!"
    cancelled: "&cᴛᴇʟᴇᴘᴏʀᴛ ᴄᴀɴᴄᴇʟʟᴇᴅ!"
    spawn-not-set: "&csᴘᴀᴡɴ ɪs ɴᴏᴛ sᴇᴛ!"

spawn: {}
```

## Installation

1. Download `NextSpawn-1.0.0.jar` from your build output.
2. Put it into your server `plugins/` folder.
3. Restart the server.

## Build

```bash
./gradlew clean build
```

The compiled JAR will be located in `build/libs/`.

## Author

- Nrleryx
