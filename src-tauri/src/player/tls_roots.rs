//! The Windows root certificates as a PEM file for mpv's `tls-ca-file`: FFmpeg's
//! OpenSSL has no certificate store of its own (ADR 0026).

use std::io;
use std::path::Path;

use base64::Engine;

/// Writes the roots Windows trusts for server authentication to `path`. Fails rather
/// than leave the player with no or stale roots to validate against.
pub fn refresh(path: &Path) -> io::Result<()> {
    let roots = windows_roots()?;
    if roots.is_empty() {
        return Err(io::Error::other("aucun certificat racine"));
    }
    write_if_changed(path, &to_pem(&roots))
}

#[cfg(windows)]
fn windows_roots() -> io::Result<Vec<Vec<u8>>> {
    use schannel::cert_store::CertStore;
    use std::collections::HashSet;

    let disallowed: HashSet<Vec<u8>> = CertStore::open_current_user("Disallowed")?
        .certs()
        .map(|cert| cert.to_der().to_vec())
        .collect();
    let mut roots = Vec::new();
    // The current user's view includes the machine's roots.
    for cert in CertStore::open_current_user("ROOT")?.certs() {
        if allows_server_auth(&cert.valid_uses()?)
            && cert.is_time_valid()?
            && !disallowed.contains(cert.to_der())
        {
            roots.push(cert.to_der().to_vec());
        }
    }
    Ok(roots)
}

#[cfg(not(windows))]
fn windows_roots() -> io::Result<Vec<Vec<u8>>> {
    Err(io::Error::other(
        "magasin de certificats Windows uniquement",
    ))
}

#[cfg(windows)]
fn allows_server_auth(uses: &schannel::cert_context::ValidUses) -> bool {
    use schannel::cert_context::ValidUses;

    const SERVER_AUTH_OID: &str = "1.3.6.1.5.5.7.3.1";
    match uses {
        ValidUses::All => true,
        ValidUses::Oids(oids) => oids.iter().any(|oid| oid == SERVER_AUTH_OID),
    }
}

fn to_pem(certs: &[Vec<u8>]) -> String {
    let mut pem = String::new();
    for der in certs {
        pem.push_str("-----BEGIN CERTIFICATE-----\n");
        let encoded = base64::engine::general_purpose::STANDARD.encode(der);
        for line in encoded.as_bytes().chunks(64) {
            // Base64 is ASCII, so every chunk is valid UTF-8.
            pem.push_str(&String::from_utf8_lossy(line));
            pem.push('\n');
        }
        pem.push_str("-----END CERTIFICATE-----\n");
    }
    pem
}

/// Replaces the file by renaming, so a reader never sees half of it, and leaves an
/// identical file alone: the player may be reading it.
fn write_if_changed(path: &Path, content: &str) -> io::Result<()> {
    if std::fs::read(path).is_ok_and(|current| current == content.as_bytes()) {
        return Ok(());
    }
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension("tmp");
    std::fs::write(&temporary, content)?;
    std::fs::rename(&temporary, path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pem_wraps_at_64_characters() {
        assert_eq!(
            to_pem(&[vec![1, 2, 3]]),
            "-----BEGIN CERTIFICATE-----\nAQID\n-----END CERTIFICATE-----\n"
        );
        let pem = to_pem(&[vec![0; 49], vec![0; 3]]);
        let lines: Vec<&str> = pem.lines().collect();
        assert_eq!(lines[1].len(), 64);
        assert_eq!(lines[2], "AA==");
        assert_eq!(lines[3], "-----END CERTIFICATE-----");
        assert_eq!(lines[4], "-----BEGIN CERTIFICATE-----");
        assert_eq!(lines[5], "AAAA");
    }

    #[cfg(windows)]
    #[test]
    fn only_server_authentication_roots_are_kept() {
        use schannel::cert_context::ValidUses;

        assert!(allows_server_auth(&ValidUses::All));
        assert!(allows_server_auth(&ValidUses::Oids(vec![
            "1.3.6.1.5.5.7.3.4".into(),
            "1.3.6.1.5.5.7.3.1".into(),
        ])));
        // Email only, and a root Windows has disabled for every use.
        assert!(!allows_server_auth(&ValidUses::Oids(vec![
            "1.3.6.1.5.5.7.3.4".into()
        ])));
        assert!(!allows_server_auth(&ValidUses::Oids(Vec::new())));
    }

    #[cfg(windows)]
    #[test]
    fn the_real_store_gives_certificates() {
        let roots = windows_roots().expect("store");
        assert!(!roots.is_empty());
        // Every DER certificate is an ASN.1 sequence.
        assert!(roots.iter().all(|der| der.first() == Some(&0x30)));
    }

    #[test]
    fn writes_once_and_restores_a_modified_file() {
        let dir = std::env::temp_dir().join(format!("viewer-tls-é-{}", std::process::id()));
        let path = dir.join("sub").join("tls-roots.pem");

        write_if_changed(&path, "un").unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "un");
        assert!(!path.with_extension("tmp").exists());

        // Identical content: the file is not replaced, even read-only.
        let mut permissions = std::fs::metadata(&path).unwrap().permissions();
        permissions.set_readonly(true);
        std::fs::set_permissions(&path, permissions.clone()).unwrap();
        write_if_changed(&path, "un").unwrap();
        #[allow(clippy::permissions_set_readonly_false)]
        permissions.set_readonly(false);
        std::fs::set_permissions(&path, permissions).unwrap();

        std::fs::write(&path, "modifié").unwrap();
        write_if_changed(&path, "un").unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "un");

        std::fs::remove_dir_all(&dir).unwrap();
    }
}
