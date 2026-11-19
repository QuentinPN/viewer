import { describe, expect, it } from "vitest";
import openapiRaw from "../../../contracts/request/openapi.json?raw";
import { type OpenApiDoc, type Schema, validate } from "./schemaCheck";

// Toutes les fixtures du dossier, lues comme texte brut (aucune dépendance Node).
const rawFixtures = import.meta.glob<string>(
  "../../../contracts/request/fixtures/*.json",
  { query: "?raw", import: "default", eager: true },
);

const fixtures = new Map<string, string>(
  Object.entries(rawFixtures).map(([path, text]) => [
    path.replace(/^.*\/([^/]+)\.json$/, "$1"),
    text,
  ]),
);
const readFixture = (name: string): unknown => {
  const text = fixtures.get(name);
  if (text === undefined) throw new Error(`fixture absente : ${name}`);
  return JSON.parse(text);
};

type FullDoc = OpenApiDoc & {
  paths: Record<
    string,
    Record<
      string,
      {
        responses: Record<
          string,
          { content: Record<string, { schema: Schema }> }
        >;
      }
    >
  >;
};
const doc = JSON.parse(openapiRaw) as FullDoc;

const ref = (name: string): Schema => ({
  $ref: `#/components/schemas/${name}`,
});
const sessionsSchema =
  doc.paths["/api/auth/sessions"]?.get?.responses["200"]?.content[
    "application/json"
  ]?.schema;
if (!sessionsSchema)
  throw new Error("schéma GET /api/auth/sessions introuvable");

// Fixture (sans extension) -> schéma OpenAPI.
const schemaFor: Record<string, Schema> = {
  "version-ok": ref("ApiVersion"),
  "version-old": ref("ApiVersion"),
  "emby-token": ref("EmbyTokenSession"),
  sessions: sessionsSchema,
  discover: ref("DiscoverResponse"),
  "paged-media": ref("PagedMedia"),
  search: ref("MediaSearchResults"),
  "media-movie": ref("MediaDetails"),
  "media-tv": ref("MediaDetails"),
  quota: ref("RequestQuota"),
  "request-view": ref("RequestView"),
  "request-page": ref("RequestPage"),
  "request-detail": ref("RequestDetail"),
  error: ref("ApiError"),
};
const check = (name: string, value: unknown): string[] => {
  const schema = schemaFor[name];
  if (!schema) throw new Error(`pas de schéma pour ${name}`);
  return validate(doc, schema, value);
};

describe("contrat Request", () => {
  it("chaque fixture est associée à un schéma", () => {
    expect([...fixtures.keys()].sort()).toEqual(Object.keys(schemaFor).sort());
  });

  it.each(Object.keys(schemaFor))(
    "la fixture %s respecte le schéma",
    (name) => {
      expect(check(name, readFixture(name))).toEqual([]);
    },
  );

  it("refuse une propriété inconnue", () => {
    const fixture = readFixture("quota") as Record<string, unknown>;
    fixture.surprise = 1;
    expect(check("quota", fixture)).toContain("$.surprise: propriété inconnue");
  });

  it("refuse un champ requis absent", () => {
    const fixture = readFixture("media-movie") as Record<string, unknown>;
    delete fixture.embyItemId;
    expect(check("media-movie", fixture)).toContain(
      "$.embyItemId: champ requis absent",
    );
  });

  it("refuse une valeur hors enum dans un schéma imbriqué", () => {
    const fixture = readFixture("request-page") as {
      results: { status: string }[];
    };
    const first = fixture.results[0];
    if (!first) throw new Error("fixture vide");
    first.status = "inconnu";
    expect(check("request-page", fixture)).not.toEqual([]);
  });

  it("media-tv couvre les saisons attendues", () => {
    const tv = readFixture("media-tv") as {
      seasons: { seasonNumber: number }[];
    };
    expect(tv.seasons.map((s) => s.seasonNumber)).toEqual([0, 1, 2, 3, 4, 5]);
  });
});
