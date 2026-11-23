package redact

import (
	"errors"
	"fmt"
)

// Masked is what a type holding a secret prints under every fmt verb. Types
// implement fmt.Formatter with WriteMasked (String and GoString alone miss %d, and
// fmt cannot call them on an unexported field), so no verb reaches the raw fields.
func Masked(name string) string { return name + "(***)" }

// WriteMasked writes Masked(name) to f: the body of such a type's Format.
func WriteMasked(f fmt.State, name string) { _, _ = f.Write([]byte(Masked(name))) }

// RefuseMarshal is the MarshalJSON of a type holding a secret: it never serializes.
func RefuseMarshal(name string) ([]byte, error) {
	return nil, errors.New(name + " ne se sérialise pas")
}
