package io.github.quentinpn.viewer.tv.update

import io.github.quentinpn.viewer.tv.ui.updateStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class ManualCheckTest {
    @Test
    fun theStatusSaysWhatRunsThenTheResult() {
        assertEquals("", updateStatus(null, null, null))
        assertEquals("Recherche…", updateStatus(CheckActivity.Checking, CheckResult.UpToDate, null))
        assertEquals("Téléchargement…", updateStatus(CheckActivity.Downloading, null, null))
        assertEquals("Viewer est à jour", updateStatus(null, CheckResult.UpToDate, null))
        assertEquals("Version 1.2.0 prête", updateStatus(null, CheckResult.Ready("1.2.0"), "1.2.0"))
        // A version downloaded in the background shows without a manual check
        assertEquals("Version 1.2.0 prête", updateStatus(null, null, "1.2.0"))
        assertEquals("Oups", updateStatus(null, CheckResult.Failed("Oups"), null))
    }

    @Test
    fun failuresAreFrench() {
        assertEquals(
            "Serveur de mises à jour injoignable. Il ne répond que sur le réseau de la maison.",
            checkFailure(UnknownHostException("192.168.1.14")),
        )
        assertEquals(checkFailure(IOException()), checkFailure(UnknownHostException()))
        assertEquals("Le manifeste de mise à jour est illisible.", checkFailure(ManifestException("Le manifeste de mise à jour est illisible.")))
        assertEquals("Android a refusé la vérification.", checkFailure(SecurityException()))
    }
}
