# Ménage des worktrees

## 2026-11-19 — `/clean-worktrees`

61 worktrees supprimés : leur branche était déjà fusionnée dans `main` et ils n'avaient aucun fichier non commité. Les 61 branches locales ont ensuite été supprimées (`git branch -d`, sauf `claude/tv-apps-webos-firestick-androidtv-4a4d94` en `-D` : fusionnée dans `main` mais en avance sur sa copie `github/`) ; les branches distantes n'ont pas été touchées. Sur 50 d'entre eux, `git worktree remove` a désinscrit le worktree mais n'a pas pu effacer le dossier (« Filename too long ») : ces dossiers résiduels, sans lien `.git`, ont été effacés à part (robocopy /MIR).

| Worktree | Branche | Commit |
|---|---|---|
| `.claude/worktrees/agent-a001d8a011de7e665` | `worktree-agent-a001d8a011de7e665` | `34932c5` |
| `.claude/worktrees/agent-a0f0ff70812779555` | `worktree-agent-a0f0ff70812779555` | `ce25471` |
| `.claude/worktrees/agent-a10975782922ec0d2` | `worktree-agent-a10975782922ec0d2` | `cc55208` |
| `.claude/worktrees/agent-a1357784bbedad443` | `worktree-agent-a1357784bbedad443` | `748f479` |
| `.claude/worktrees/agent-a1427a884fbe5119e` | `worktree-agent-a1427a884fbe5119e` | `70aa97a` |
| `.claude/worktrees/agent-a179ed4e4db2027f4` | `worktree-agent-a179ed4e4db2027f4` | `00f131b` |
| `.claude/worktrees/agent-a18fc41b8ca1bcd88` | `worktree-agent-a18fc41b8ca1bcd88` | `f313014` |
| `.claude/worktrees/agent-a194677a08335ce85` | `worktree-agent-a194677a08335ce85` | `225ed40` |
| `.claude/worktrees/agent-a1a0bab27a2b4009a` | `worktree-agent-a1a0bab27a2b4009a` | `8c0dea2` |
| `.claude/worktrees/agent-a1f0d0dc7344e3e32` | `worktree-agent-a1f0d0dc7344e3e32` | `6073c80` |
| `.claude/worktrees/agent-a1f1754f2c18130db` | `worktree-agent-a1f1754f2c18130db` | `519de18` |
| `.claude/worktrees/agent-a1fcbb26b590e88b4` | `worktree-agent-a1fcbb26b590e88b4` | `e93d229` |
| `.claude/worktrees/agent-a22bbedb3a68503e1` | `worktree-agent-a22bbedb3a68503e1` | `2a56164` |
| `.claude/worktrees/agent-a25daafddca44ea08` | `worktree-agent-a25daafddca44ea08` | `0676e25` |
| `.claude/worktrees/agent-a26251b3d53b28e97` | `worktree-agent-a26251b3d53b28e97` | `83a73e9` |
| `.claude/worktrees/agent-a26bb37d4a0ab7303` | `worktree-agent-a26bb37d4a0ab7303` | `46888ac` |
| `.claude/worktrees/agent-a29fb0dc327fdacbc` | `worktree-agent-a29fb0dc327fdacbc` | `c19e306` |
| `.claude/worktrees/agent-a373b87cebc09ddb1` | `worktree-agent-a373b87cebc09ddb1` | `bcdcd89` |
| `.claude/worktrees/agent-a3c469875572c9652` | `worktree-agent-a3c469875572c9652` | `7ff233e` |
| `.claude/worktrees/agent-a4254c89b7bd1eea2` | `worktree-agent-a4254c89b7bd1eea2` | `8bf7a76` |
| `.claude/worktrees/agent-a43e423309591a68c` | `worktree-agent-a43e423309591a68c` | `70171f0` |
| `.claude/worktrees/agent-a447c1a646b36838a` | `worktree-agent-a447c1a646b36838a` | `f269f2d` |
| `.claude/worktrees/agent-a45191405c7306268` | `worktree-agent-a45191405c7306268` | `f39a61e` |
| `.claude/worktrees/agent-a4ad9973208153624` | `worktree-agent-a4ad9973208153624` | `8be57d3` |
| `.claude/worktrees/agent-a5f242f79ea3436ac` | `worktree-agent-a5f242f79ea3436ac` | `3f200d3` |
| `.claude/worktrees/agent-a6631a62374f413a9` | `worktree-agent-a6631a62374f413a9` | `386ce25` |
| `.claude/worktrees/agent-a667d69e018740530` | `worktree-agent-a667d69e018740530` | `b523cd3` |
| `.claude/worktrees/agent-a67cd42aa71623377` | `worktree-agent-a67cd42aa71623377` | `4b158f8` |
| `.claude/worktrees/agent-a701551a464da4181` | `worktree-agent-a701551a464da4181` | `05e6da6` |
| `.claude/worktrees/agent-a712b58b0f8e3b509` | `worktree-agent-a712b58b0f8e3b509` | `57a8bef` |
| `.claude/worktrees/agent-a74617356e8c48e98` | `worktree-agent-a74617356e8c48e98` | `4a19884` |
| `.claude/worktrees/agent-a7d50274401ae7216` | `worktree-agent-a7d50274401ae7216` | `6a8dba7` |
| `.claude/worktrees/agent-a7d5e58ae823a8125` | `worktree-agent-a7d5e58ae823a8125` | `8cbe0e3` |
| `.claude/worktrees/agent-a99e8bd9c8dcc0258` | `worktree-agent-a99e8bd9c8dcc0258` | `e49f2b7` |
| `.claude/worktrees/agent-a9e9ac42acdab6e45` | `worktree-agent-a9e9ac42acdab6e45` | `3254e2b` |
| `.claude/worktrees/agent-aa717f4eceb56e5d9` | `worktree-agent-aa717f4eceb56e5d9` | `fa28dfb` |
| `.claude/worktrees/agent-ab2f88f5b257aeab2` | `worktree-agent-ab2f88f5b257aeab2` | `bea6b60` |
| `.claude/worktrees/agent-ab3f05ebdb87f9534` | `worktree-agent-ab3f05ebdb87f9534` | `541be6b` |
| `.claude/worktrees/agent-ab58a77881f6492c0` | `worktree-agent-ab58a77881f6492c0` | `4aa459b` |
| `.claude/worktrees/agent-abb3729a5302f98d6` | `worktree-agent-abb3729a5302f98d6` | `a3c8a43` |
| `.claude/worktrees/agent-abeefe9bde0c55811` | `worktree-agent-abeefe9bde0c55811` | `28d69f5` |
| `.claude/worktrees/agent-abf4134ab5137146c` | `worktree-agent-abf4134ab5137146c` | `391a33d` |
| `.claude/worktrees/agent-ac3940241787954d5` | `worktree-agent-ac3940241787954d5` | `a20fd42` |
| `.claude/worktrees/agent-ac8a0e0704c001cee` | `worktree-agent-ac8a0e0704c001cee` | `48a2493` |
| `.claude/worktrees/agent-acc0b764bd48b1e6c` | `worktree-agent-acc0b764bd48b1e6c` | `3f16c21` |
| `.claude/worktrees/agent-acd416a5f5ca5b93b` | `worktree-agent-acd416a5f5ca5b93b` | `750b865` |
| `.claude/worktrees/agent-ad078cb1f8b7a3631` | `worktree-agent-ad078cb1f8b7a3631` | `c222d14` |
| `.claude/worktrees/agent-ad1289b5ae2250500` | `worktree-agent-ad1289b5ae2250500` | `7e601ea` |
| `.claude/worktrees/agent-ad1ae87563f00449a` | `worktree-agent-ad1ae87563f00449a` | `e1f02fe` |
| `.claude/worktrees/agent-ad519f4c39d63dda1` | `worktree-agent-ad519f4c39d63dda1` | `a4b5495` |
| `.claude/worktrees/agent-aeb82d285a24f3570` | `worktree-agent-aeb82d285a24f3570` | `2f8b1b2` |
| `.claude/worktrees/agent-aec32a6a837d6fe3f` | `worktree-agent-aec32a6a837d6fe3f` | `3875298` |
| `.claude/worktrees/agent-af43e9fb951805323` | `worktree-agent-af43e9fb951805323` | `6759041` |
| `.claude/worktrees/agent-af92e4bf92d9d273d` | `worktree-agent-af92e4bf92d9d273d` | `c107efc` |
| `.claude/worktrees/agent-aff945413ff0294ef` | `worktree-agent-aff945413ff0294ef` | `dda39ce` |
| `.claude/worktrees/bitrate-63e20f` | `claude/tv-apps-webos-firestick-androidtv-4a4d94` | `bef7a4d` |
| `.claude/worktrees/discord-screen-share-tv-sync-52d47c` | `claude/discord-screen-share-tv-sync-52d47c` | `ee55f5c` |
| `.claude/worktrees/prochaine-etape-4ea34a` | `claude/gitea-api-changes-28-3-c113a7` | `0529896` |
| `.claude/worktrees/review-merge-open-prs-4d9baf` | `claude/tv-buffering-skip-intro-0f7889` | `144e293` |
| `.claude/worktrees/synchro-tv-discord-666b83` | `claude/synchro-tv-discord-666b83` | `d766db9` |
| `.claude/worktrees/viewer-fullscreen-default-022619` | `claude/auto-refresh-home-feed-1d0d40` | `1ba8866` |

Ensuite, 5 autres branches locales déjà fusionnées dans `main` et sans worktree ont aussi été supprimées (`git branch -d`) : `claude/adoring-elbakyan-a9ac5f`, `claude/agitated-pascal-0c6467`, `claude/review-merge-open-prs-4d9baf`, `claude/skip-button-autohide`, `claude/wizardly-jennings-974fb2`. Il ne reste que `main`.

Branches distantes : `claude/tv-apps-webos-firestick-androidtv-4a4d94` (fusionnée dans `main`) supprimée sur `origin` et `github`. Les deux remotes n'ont plus que `main`.
