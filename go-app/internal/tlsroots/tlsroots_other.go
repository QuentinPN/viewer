//go:build !windows

package tlsroots

import "errors"

func windowsRoots() ([][]byte, error) {
	return nil, errors.New("magasin de certificats Windows uniquement")
}
