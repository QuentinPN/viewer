// Validateur JSON Schema minimal et strict, limité aux mots-clés utilisés par
// le contrat Request. Toute propriété absente du schéma est une erreur.
export type Schema = {
  $ref?: string;
  type?: string | string[];
  properties?: Record<string, Schema>;
  required?: string[];
  enum?: unknown[];
  const?: unknown;
  anyOf?: Schema[];
  oneOf?: Schema[];
  items?: Schema;
  pattern?: string;
  minimum?: number;
  maximum?: number;
};

export type OpenApiDoc = { components: { schemas: Record<string, Schema> } };

function typeOf(value: unknown): string {
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  if (typeof value === "number")
    return Number.isInteger(value) ? "integer" : "number";
  return typeof value;
}

function typeMatches(expected: string, actual: string): boolean {
  return expected === actual || (expected === "number" && actual === "integer");
}

export function validate(
  doc: OpenApiDoc,
  schema: Schema,
  value: unknown,
  path = "$",
): string[] {
  if (schema.$ref) {
    const name = schema.$ref.replace("#/components/schemas/", "");
    const target = doc.components.schemas[name];
    if (!target) return [`${path}: $ref inconnu ${schema.$ref}`];
    return validate(doc, target, value, path);
  }
  if (schema.anyOf || schema.oneOf) {
    const branches = (schema.anyOf ?? schema.oneOf) as Schema[];
    const results = branches.map((b) => validate(doc, b, value, path));
    const ok = results.filter((r) => r.length === 0).length;
    const exact = schema.oneOf ? ok === 1 : ok >= 1;
    return exact
      ? []
      : [
          `${path}: aucune branche ne correspond (${results.map((r) => r[0]).join(" | ")})`,
        ];
  }
  const errors: string[] = [];
  const actual = typeOf(value);
  if (schema.type) {
    const types = Array.isArray(schema.type) ? schema.type : [schema.type];
    if (!types.some((t) => typeMatches(t, actual))) {
      return [`${path}: type ${actual} au lieu de ${types.join("|")}`];
    }
  }
  if (schema.const !== undefined && value !== schema.const)
    errors.push(`${path}: attendu ${String(schema.const)}`);
  if (schema.enum && !schema.enum.includes(value))
    errors.push(`${path}: valeur hors enum ${String(value)}`);
  if (
    typeof value === "string" &&
    schema.pattern &&
    !new RegExp(schema.pattern).test(value)
  ) {
    errors.push(`${path}: ne respecte pas le motif`);
  }
  if (typeof value === "number") {
    if (schema.minimum !== undefined && value < schema.minimum)
      errors.push(`${path}: sous le minimum`);
    if (schema.maximum !== undefined && value > schema.maximum)
      errors.push(`${path}: au-dessus du maximum`);
  }
  if (actual === "object" && schema.properties) {
    const obj = value as Record<string, unknown>;
    for (const key of schema.required ?? []) {
      if (!(key in obj)) errors.push(`${path}.${key}: champ requis absent`);
    }
    for (const [key, v] of Object.entries(obj)) {
      const sub = schema.properties[key];
      if (!sub) errors.push(`${path}.${key}: propriété inconnue`);
      else errors.push(...validate(doc, sub, v, `${path}.${key}`));
    }
  }
  if (actual === "array" && schema.items) {
    (value as unknown[]).forEach((v, i) => {
      errors.push(...validate(doc, schema.items as Schema, v, `${path}[${i}]`));
    });
  }
  return errors;
}
