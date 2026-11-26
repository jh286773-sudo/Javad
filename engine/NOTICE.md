# Javad signal engine — source and license notice

The FVG and Auto Threshold calculation is an adaptation of the public **Smart Money Concepts [LuxAlgo]** indicator logic by LuxAlgo.

- Original author: © LuxAlgo
- Public reference mirror: https://github.com/toikade/LuxAlgoScriptsAndOthers
- License: **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International (CC BY-NC-SA 4.0)**
- License text: https://creativecommons.org/licenses/by-nc-sa/4.0/

This adapted module is intended for non-commercial use and is distributed under the same CC BY-NC-SA 4.0 terms. Attribution and the license notice must be retained in copies and adaptations.

The repository also contains a read-only Toobit perpetual-contract catalogue client, historical candle loader, WebSocket kline stream, single-timeframe settings, per-symbol direction suppression, and platform-independent monitoring recovery policy. These engine components are not, by themselves, a complete Android application. The Android UI, Android foreground-service/boot integration, persistent state wiring, end-to-end historical backfill after stream reconnect, and user-visible signal notification still require implementation and device verification.
