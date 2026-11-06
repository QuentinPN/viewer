// Shared test data for the connection machine and coordinator tests.
import type { SavedServer, SignedInUser } from "../emby/types";

export const user: SignedInUser = {
  server: {
    url: "http://emby:8096",
    name: "Emby",
    version: "4.10.1.0",
    id: "srv1",
    encrypted: false,
  },
  userId: "u1",
  userName: "quentin",
  isAdministrator: false,
};

export const saved: SavedServer = {
  url: "http://emby:8096",
  id: "srv1",
  name: "Emby",
  version: "4.10.1.0",
  encrypted: false,
  userId: "u1",
  userName: "quentin",
};
