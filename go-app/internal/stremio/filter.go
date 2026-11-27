package stremio

import (
	"slices"
	"strings"
)

// Serves tells whether the addon is asked for streams of id: it declares the
// `stream` resource for type t and, when idPrefixes are declared, one of them
// starts id. An object-form resource's types and idPrefixes override the
// manifest's; when absent, the manifest's apply (no idPrefixes at all: any id).
func (a Addon) Serves(t ContentType, id string) bool {
	for _, r := range a.resources {
		if r.name != "stream" {
			continue
		}
		types, prefixes := r.types, r.idPrefixes
		if types == nil {
			types = a.types
		}
		if prefixes == nil {
			prefixes = a.idPrefixes
		}
		if !slices.Contains(types, string(t)) {
			continue
		}
		if len(prefixes) == 0 || slices.ContainsFunc(prefixes, func(p string) bool { return strings.HasPrefix(id, p) }) {
			return true
		}
	}
	return false
}
