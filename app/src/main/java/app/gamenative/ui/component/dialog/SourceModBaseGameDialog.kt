package app.gamenative.ui.component.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.model.LibraryViewModel
import app.gamenative.utils.SourceModDetector

/**
 * Shown when a picked/imported Custom Game folder turns out to be a bare Source or GoldSrc mod
 * (a `gameinfo.txt`/`liblist.gam` with no engine of its own) — the user picks which already
 * installed game should provide the engine, and the mod is wired to launch through it.
 */
@Composable
fun SourceModBaseGameDialog(
    request: LibraryViewModel.SourceModImportRequest?,
    onSelect: (LibraryViewModel.SourceModImportRequest, SourceModDetector.EngineCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    if (request == null) return

    val engineLabel = when (request.modInfo.engineType) {
        SourceModDetector.EngineType.GOLDSRC -> stringResource(R.string.source_mod_engine_goldsrc)
        SourceModDetector.EngineType.SOURCE -> stringResource(R.string.source_mod_engine_source)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_mod_import_title, request.modInfo.displayName)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (request.candidates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.source_mod_no_base_game, engineLabel),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.source_mod_pick_base_game, engineLabel),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    request.candidates.forEach { candidate ->
                        Text(
                            text = candidate.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(request, candidate) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
