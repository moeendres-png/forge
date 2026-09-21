# WS233 S2 Contract Reconstruction

WS231 S2 rule: PASS iff 2P–5P constructible via the production provider path
AND Commander/multiplayer viable at CODE_DERIVED minimum.

WS233 production-supported technical cardinality: MIN 2 / MAX 5, set {2,3,4,5}.
1P/0P/6P+ fail closed (`PLAYER_COUNT_UNSUPPORTED`, pre-session).

Per-count qualifying lifecycle (each N in 2..5, in-JVM and fresh-process):

1. exactly N valid deck handles accepted;
2. session constructible; 3. game starts;
4. exactly N Forge Players; 5. N distinct stable principal identities;
5. all controllers external; 7. none AI;
6. starting-player frame Rules-Core-owned, domain exactly N, explicit submit
   (last seat chosen; no default);
7. mulligans progress without defaults; 11. first priority reachable;
8. pass_priority exposed; 13. valid submit advances state (hash change);
9. projection has exactly N public players; 15. principal scoping intact;
10. starting life 40 from Forge lifecycle; 17. turn order exactly live roster;
11. no fixed-four blocker; 19. clean shutdown; 20. reusable handles, no
    cross-session contamination.

S2 PASS requires 2P+3P+4P+5P PASS. 6P failure is conformance (not a defect).
Constructibility/lifecycle evidence is NOT a claim of full Commander
multiplayer semantics (APNAP/800.4/Commander-damage remain S3/S5).
