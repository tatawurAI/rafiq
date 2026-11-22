package ai.tatawur.rafiq.pairing

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.pairing.CodePairResult
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Pairing, empty", showSystemUi = true)
@Composable
private fun PairingEmptyPreview() {
    PairingPreview(url = "", code = "", failure = null)
}

@Preview(name = "Pairing, removed by the server", showSystemUi = true)
@Composable
private fun PairingRevokedPreview() {
    PairingPreview(url = "https://server.example.ts.net", code = "", failure = null, revoked = true)
}

@Preview(name = "Pairing, code rejected", showSystemUi = true)
@Composable
private fun PairingCodeRejectedPreview() {
    PairingPreview(url = "https://server.example.ts.net", code = "ABCD-EFGH", failure = CodePairResult.CodeRejected)
}

@Preview(name = "Pairing, unreachable, long detail", showSystemUi = true)
@Composable
private fun PairingUnreachablePreview() {
    PairingPreview(
        url = "https://server.example.ts.net",
        code = "ABCD-EFGH",
        failure =
            CodePairResult.Unreachable(
                "Unable to resolve host server.example.ts.net: No address associated with hostname",
            ),
    )
}

@Composable
private fun PairingPreview(
    url: String,
    code: String,
    failure: CodePairResult?,
    revoked: Boolean = false,
) {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PairingForm(
                PairingFormState(
                    url,
                    code,
                    notice = if (revoked) stringResource(R.string.pair_revoked_notice) else null,
                    hint = failure?.let { hintFor(it) },
                ),
                onUrlChange = {},
                onCodeChange = {},
                onDismissNotice = {},
                onConnect = {},
            )
        }
    }
}
