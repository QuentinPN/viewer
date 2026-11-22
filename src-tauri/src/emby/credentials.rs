//! Secure token storage: Windows Credential Manager in the app, an
//! in-memory store for tests. Tokens never go to disk files, SQLite or logs.

use std::collections::HashMap;
use std::sync::Mutex;

use super::EmbyError;
use super::client::AccessToken;

/// Debug builds keep their tokens apart from the installed app: scripted
/// UI tests never read, replace or delete the owner's session.
pub const SERVICE: &str = if cfg!(debug_assertions) {
    "Viewer-debug"
} else {
    "Viewer"
};

pub trait CredentialStore: Send + Sync {
    fn save(&self, account: &str, token: &AccessToken) -> Result<(), EmbyError>;
    fn load(&self, account: &str) -> Result<Option<AccessToken>, EmbyError>;
    fn delete(&self, account: &str) -> Result<(), EmbyError>;
}

/// One store shared by the Emby and Request services.
impl<T: CredentialStore + ?Sized> CredentialStore for std::sync::Arc<T> {
    fn save(&self, account: &str, token: &AccessToken) -> Result<(), EmbyError> {
        (**self).save(account, token)
    }

    fn load(&self, account: &str) -> Result<Option<AccessToken>, EmbyError> {
        (**self).load(account)
    }

    fn delete(&self, account: &str) -> Result<(), EmbyError> {
        (**self).delete(account)
    }
}

/// Credential account name for one user on one server.
pub fn account(server_id: &str, user_id: &str) -> String {
    format!("{server_id}/{user_id}")
}

#[cfg(windows)]
pub struct WindowsCredentialStore;

#[cfg(windows)]
impl WindowsCredentialStore {
    fn entry(account: &str) -> Result<keyring::Entry, EmbyError> {
        keyring::Entry::new(SERVICE, account).map_err(store_error)
    }
}

#[cfg(windows)]
fn store_error(e: keyring::Error) -> EmbyError {
    EmbyError::CredentialStore(e.to_string())
}

#[cfg(windows)]
impl CredentialStore for WindowsCredentialStore {
    fn save(&self, account: &str, token: &AccessToken) -> Result<(), EmbyError> {
        Self::entry(account)?
            .set_password(token.expose())
            .map_err(store_error)
    }

    fn load(&self, account: &str) -> Result<Option<AccessToken>, EmbyError> {
        match Self::entry(account)?.get_password() {
            Ok(t) => Ok(Some(AccessToken::new(t))),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(store_error(e)),
        }
    }

    fn delete(&self, account: &str) -> Result<(), EmbyError> {
        match Self::entry(account)?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(e) => Err(store_error(e)),
        }
    }
}

#[derive(Default)]
pub struct MemoryCredentialStore(Mutex<HashMap<String, AccessToken>>);

impl CredentialStore for MemoryCredentialStore {
    fn save(&self, account: &str, token: &AccessToken) -> Result<(), EmbyError> {
        self.0
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(account.to_owned(), token.clone());
        Ok(())
    }

    fn load(&self, account: &str) -> Result<Option<AccessToken>, EmbyError> {
        Ok(self
            .0
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(account)
            .cloned())
    }

    fn delete(&self, account: &str) -> Result<(), EmbyError> {
        self.0
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(account);
        Ok(())
    }
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;

    /// Writes, reads and deletes a uniquely named entry in the real Windows
    /// Credential Manager. Ignored by default: it touches the user's vault.
    #[test]
    #[ignore = "writes a temporary entry to the Windows Credential Manager"]
    fn windows_store_round_trip() {
        let store = WindowsCredentialStore;
        let account = format!("test/{}", uuid::Uuid::new_v4());
        let token = AccessToken::new("round-trip-token".into());
        assert!(store.load(&account).unwrap().is_none());
        store.save(&account, &token).unwrap();
        assert_eq!(store.load(&account).unwrap(), Some(token));
        store.delete(&account).unwrap();
        assert!(store.load(&account).unwrap().is_none());
        // Deleting a missing entry is not an error (sign-out after a lost token).
        store.delete(&account).unwrap();
    }
}
