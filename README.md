# XaeroPlus Chunk Follower

<p align="center">
  <img src="https://github.com/user-attachments/assets/ee29b94e-5d57-4c55-96d4-6fb1b8375eaa" alt="Elytra Trail Follower Demonstration">
</p>
<p align="center">
  (click play to check the module in action!)
</p>

<p align="center">
  <img src="https://img.shields.io/badge/MC-1.21.4-brightgreen.svg" alt="Minecraft"/>
</p>

## Disclaimer

* This module was created purely as a PoC, do not expect serious support or constant updates from this, if you know what you are doing, you could port this fork into any 1.21+ version that you wish
* Currently, the 1.21.11 port is being kept as private since it has some extra features that are not supported on the public release.

## Credits and Original Mod

This module is built as an extension for [XaeroPlus by rfresh2](https://github.com/rfresh2/XaeroPlus), which enhances the original Xaero's Minimap/Worldmap mods.

All foundational map-reading logic, chunk tracking and UI integration are built upon the excellent work of the original XaeroPlus team and contribuitors.

XaeroPlus is not affiliated or endorsed by xaero96. Please report issues to XaeroPlus's [Github](https://github.com/rfresh2/XaeroPlus/issues) or [discord server](https://discord.gg/nJZrSaRKtb).

<details>
<summary>Example Map</summary>
<p align="center">
  <img src="https://i.imgur.com/oYYhDoS.jpeg">
</p>
</details>

## How it works

* The `ElytraTrailFollower` acts as a some sort of "Copilot" or macro-navigator. While Baritone handles the micro-navigation (aka dodging blocks, managing fireworks and flying), this mod analyzes
  the minimap on a large scale to decide **which chunk** Baritone should fly towards next, ensuring the physical route consist purely of visted chunks (OldChunks)

## Core Features

1. **OldChunk Recognition**: The mod seamlessly reads XaeroPlus's internal data to distinguish between OldChunks (older terrain) and NewChunks (ungenerated or newer terrain).
2. **Safe Pathfinding**: Before issuing a command to Baritone, it scans the area, generating an unbroken chain of OldChunks towards your destination, preventing the Elytra from flying blindly into ungenerated territory.
3. **Persistent Navigation**: If Baritone gets stuck on a block or finishes a short sub-path, the follower will persistently recalculate from that spot and restart Baritone until the final destination is reached.
4. **Automatic Cancellation**: If the OldChunk trail completely ends or hits a true dead end with no available path, the mod immediately intervenes and aborts Baritone's ElytraFly process (`cancelEverything()`) to stop you mid-air before entering a dangerous NewChunk.

## Navigation Modes (Algorithms)

The chunk scanning behavior can be altered via the `Elytra Follow Algorithm` setting in the Xaero options:

### 1. DIRECT
- **How it works**: Locks onto the initial angle you clicked. It searches for the furthest continuous OldChunk within your radius, attempting to strictly adhere to a straight line along that original angle.
- **Best use scenario**: Useful for predictable paths, straight highways, or linear travel. Because it is strict, if it encounters a complex fork or a lost trail, it will simply abort the flight for safety. No chunk can be visited twice.

### 2. TREMAUX
- **How it works**: Implements the mathematical logic of the classic [Trémaux's algorithm](https://en.wikipedia.org/wiki/Maze-solving_algorithm#Trémaux's_algorithm) for solving mazes. The code tracks and memorizes in a `HashMap` exactly how many times you have stepped on the same chunk during this specific trip.
- **Best use scenario**: Perfect for chaotic trails that end abruptly. Trémaux allows the bot to exceptionally turn around and retrace its steps (visiting the same chunk for a second time) to escape a dead-end tunnel and resume an alternative route. A chunk visited *twice* is permanently blocked as "dead".

### 3. A-STAR (A*)
- **How it works**: Modernizes the search by implementing sophisticated heuristic pathfinding handled by a Priority Queue (`PriorityQueue`). Starting from the player, it floods the geographical OldChunks evaluating two vital costs at every path node:
  - `G-Cost` (Distance traveled): How many chunk hops it took from the player to reach that point.
  - `H-Cost` (Heuristic): The mathematical straight-line distance separating that chunk from the **Final Clicked WayPoint**.
- **Best use scenario**: The most recommended and intelligent mode. It will seamlessly navigate blind corners, tight "U" turns, or heavily zigzagging maps, always mathematically knowing which available OldChunk curve immovably brings you closer to your destination coordinates, rather than just gazing at the strict horizon.

## Elytra Smart Mode (Dynamic Adjustments)

An additional setting (`Elytra Smart Mode`) optimizes the follower's behavior based on real-world server events:

- **Dynamic Latency Adjustment (TPS/Ping)**: If you suffer severe server lag spikes where your ping shoots above 80ms, the mod detects it directly from the Minecraft connection object and instantly increases your tick actuation wait time (up to doubling the `Tick Delay`). This relieves the server by mitigating packet spam and prevents abrupt disconnections from anti-cheats.
- **Curve Prediction (Momentum)**: When flying at extremely high speeds with fireworks, it is very easy to overshoot tight curves and fly into NewChunks. The *Smart Mode* reads your current mass vector and horizontal velocity; if you exceed a critical speed threshold, it will multiply the **Chunk Search Radius (`x 1.5`)**. By pushing the radius out, the bot can project the path much further ahead than usual, issuing a sharp turn command to Baritone long before you overshoot the brakes.athTo(new BlockPos(...))` on the designed chunks without collapsing the current thread.






