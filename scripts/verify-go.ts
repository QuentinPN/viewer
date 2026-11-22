// node scripts/verify-go.ts (`npm run verify:go`): the Go gates of go-app,
// as publish.ts runs them (step.ts goGates).
import { goGates } from "./step.ts";

goGates();
