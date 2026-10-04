# LODgen

Generate LODs for DH or Voxy wihtout saving chunks

this mod is intened to be used with c2me + opencl (haven't tested without it)

should perform about the same as chunky, in same case can be faster as this mod can skip parts of generation like caves / underground

## Why?
Why would we want to generate chunks and NOT save them?

chunks can eat up a large amount of storage, a 2048c radius can be over 150 gb+, when most of those are just there for LODs

since c2me + opencl can generate chunks very fast
it is reasable to just generate chunks only for lods overnight



## Performance (roughly)
*cps can very heavily based on hardware and worldgen mods

on 9950X (these are tested with full generation, may be faster if you skip caves gen)

| Worldgen | AVG CPS | Info |
| --- | ---: | --- |
| WWOO | 400 | WWOO fluctuates a lot |
| Terralith + Tectonic | 800 | |
| Vanilla | 2000 | |


### Time it takes per CPS
| Chunks | 100 CPS | 250 CPS | 500 CPS | 1000 CPS | 2000 CPS | 3000 CPS |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 256 | 10m 55s | 4m 22s | 2m 11s | 1m 5s | 32s | 21s |
| 512 | 43m 41s | 17m 28s | 8m 44s | 4m 22s | 2m 11s | 1m 27s |
| 1024 | 2h 54m 45s | 1h 9m 54s | 34m 57s | 17m 28s | 8m 44s | 5m 49s |
| 2048 | 11h 39m 3s | 4h 39m 37s | 2h 19m 48s | 1h 9m 54s | 34m 57s | 23m 18s |
| 4096 | 1d 22h 36m 12s | 18h 38m 28s | 9h 19m 14s | 4h 39m 37s | 2h 19m 48s | 1h 33m 12s |
| 8192 | 7d 18h 24m 48s | 3d 2h 33m 55s | 1d 13h 16m 57s | 18h 38m 28s | 9h 19m 14s | 6h 12m 49s |
| 16384 | 31d 1h 39m 14s | 12d 10h 15m 41s | 6d 5h 7m 50s | 3d 2h 33m 55s | 1d 13h 16m 57s | 1d 51m 18s |
| 32768 | 124d 6h 36m 58s | 49d 17h 2m 47s | 24d 20h 31m 23s | 12d 10h 15m 41s | 6d 5h 7m 50s | 4d 3h 25m 13s |
| 65536 | 497d 2h 27m 52s | 198d 20h 11m 9s | 99d 10h 5m 34s | 49d 17h 2m 47s | 24d 20h 31m 23s | 16d 13h 40m 55s |

## Current Features
- Supports Fabric and Neoforge on `1.21.1`, `26.1.2`, `26.2`, `26.3`
- Works with Distant Horizons, replaces its `FEATURES` generator with our own
  - I might change this where via DH API its locked
- Generate chunks without saving them (so they're only used for LODs)
  - can also work as a chunk pregenerator like chunky (to avoid duplicate work)
    - for example you can have a LOD radius `1024c`, but save chunks at `256c` 
- Skip parts parts of generation like caves / underground
  - this only works if the chunk is not being saved
  - can cause some visual issue like surface caves not showing (since that parts is entirely skipped)
- Command (Very basic and WIP)
  - `/lodgen start <dim> <x> <y> <radius> <saved-radius>`
    - start task to generate chunks
  - `/lodgen start <dim> <origin|current> <radius> <saved-radius>`
    - `origin` world origin
    - `current` current player position
  - `/lodgen stop` stop current task
  - `/lodgen pause` pause current task
  - `/lodgen continue` continue current task
  - `/lodgen status` status of current task

## Things to be fixed
- text and descriptions (currently its ai slop)
- much more testing different use cases
  - like testing how it works on servers (if clients gets the LODs)

## AI
This mod is mostly made using ai (gpt-6.1-sol)
