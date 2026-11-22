package request

import (
	"errors"
	"fmt"
)

// redactedText is what a type holding a token prints under every fmt verb. Types
// implement fmt.Formatter with writeRedacted (String and GoString alone miss %d, and
// fmt cannot call them on an unexported field), so no verb reaches the raw fields.
func redactedText(name string) string { return name + "(***)" }

func writeRedacted(f fmt.State, name string) { _, _ = f.Write([]byte(redactedText(name))) }

func refuseMarshal(name string) ([]byte, error) {
	return nil, errors.New(name + " ne se sérialise pas")
}
