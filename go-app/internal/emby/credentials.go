package emby

// Secure token storage (ADR 0004), port of src-tauri/src/emby/credentials.rs: the
// Windows Credential Manager in the app (credentials_windows.go), an in-memory store
// for tests. Tokens never go to disk files, SQLite or logs.

import "sync"

// Credential service names. Debug builds keep their tokens apart from the installed
// app (ADR 0105): scripted UI tests never read, replace or delete the owner's session.
// Rust picks one with `cfg!(debug_assertions)`; Go has no such switch, so the caller
// passes it to NewWindowsCredentialStore.
const (
	CredentialServiceRelease = "Viewer"
	CredentialServiceDebug   = "Viewer-debug"
)

// CredentialStore is `CredentialStore`. One store is shared by the Emby and
// Request services (ADR 0125): implementations are safe for concurrent use.
type CredentialStore interface {
	Save(account string, token AccessToken) error
	// Load returns nil, nil when nothing is stored for account.
	Load(account string) (*AccessToken, error)
	// Delete succeeds when nothing is stored (sign-out after a lost token).
	Delete(account string) error
}

// CredentialAccount is the credential account name for one user on one server.
func CredentialAccount(serverID, userID string) string {
	return serverID + "/" + userID
}

// MemoryCredentialStore is `MemoryCredentialStore`. The zero value is ready to use.
type MemoryCredentialStore struct {
	mu     sync.Mutex
	tokens map[string]AccessToken
}

// String keeps %v and %+v from printing the tokens: fmt does not call
// AccessToken.String on the values of an unexported map.
func (m *MemoryCredentialStore) String() string { return "MemoryCredentialStore(<redacted>)" }

// GoString does the same for %#v.
func (m *MemoryCredentialStore) GoString() string { return m.String() }

func (m *MemoryCredentialStore) Save(account string, token AccessToken) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.tokens == nil {
		m.tokens = make(map[string]AccessToken)
	}
	m.tokens[account] = token
	return nil
}

func (m *MemoryCredentialStore) Load(account string) (*AccessToken, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	t, ok := m.tokens[account]
	if !ok {
		return nil, nil
	}
	return &t, nil
}

func (m *MemoryCredentialStore) Delete(account string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.tokens, account)
	return nil
}
