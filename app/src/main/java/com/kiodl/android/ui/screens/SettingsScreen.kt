package com.kiodl.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kiodl.android.domain.model.AppSettings
import com.kiodl.android.domain.model.ThemeMode
import com.kiodl.android.ui.util.INFLATE_BUFFER_OPTIONS
import com.kiodl.android.ui.util.STREAM_WRITE_OPTIONS
import com.kiodl.android.ui.util.nextOption
import com.kiodl.android.ui.util.previousOption

@Composable
internal fun SettingsPage(
    settings: AppSettings,
    setSegmentPoolSize: (Int) -> Unit,
    setBandwidth: (Int) -> Unit,
    setUploadBandwidth: (Int) -> Unit,
    setDownloadRetries: (Int) -> Unit,
    setUploadRetries: (Int) -> Unit,
    setCreateCollectionSubfolder: (Boolean) -> Unit,
    setAsciiFilenames: (Boolean) -> Unit,
    setAutoTryCollectionPasswords: (Boolean) -> Unit,
    setCollectionPasswordList: (List<String>) -> Unit,
    setStreamWriteBatchBytes: (Int) -> Unit,
    setInflateBufferBytes: (Int) -> Unit,
    setThemeMode: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var passwordListText by rememberSaveable(settings.collectionPasswordList) {
        mutableStateOf(settings.collectionPasswordList.joinToString("\n"))
    }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionTitle("화면 테마")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.themeMode == mode,
                    onClick = { setThemeMode(mode) },
                    label = {
                        Text(when (mode) {
                            ThemeMode.SYSTEM -> "시스템"
                            ThemeMode.LIGHT -> "라이트"
                            ThemeMode.DARK -> "다크"
                        })
                    },
                )
            }
        }
        SectionTitle("전송")
        SettingStepper(
            title = "세그먼트 풀 크기",
            description = "다운로드·업로드 큐에 공통 적용되며 업로드는 최대 8개입니다",
            value = settings.segmentPoolSize,
            suffix = "개",
            onDecrease = { setSegmentPoolSize(settings.segmentPoolSize - 1) },
            onIncrease = { setSegmentPoolSize(settings.segmentPoolSize + 1) },
        )
        SettingStepper(
            title = "스트림 쓰기 배치",
            description = "디스크 동기화 전 묶어서 쓰는 크기",
            value = settings.streamWriteBatchBytes / 1024,
            suffix = "KiB",
            onDecrease = {
                setStreamWriteBatchBytes(previousOption(STREAM_WRITE_OPTIONS, settings.streamWriteBatchBytes))
            },
            onIncrease = {
                setStreamWriteBatchBytes(nextOption(STREAM_WRITE_OPTIONS, settings.streamWriteBatchBytes))
            },
        )
        SettingStepper(
            title = "압축 해제 버퍼",
            description = "ZIP inflate 및 검증 버퍼 크기",
            value = settings.inflateBufferBytes / (1024 * 1024),
            suffix = "MiB",
            onDecrease = { setInflateBufferBytes(previousOption(INFLATE_BUFFER_OPTIONS, settings.inflateBufferBytes)) },
            onIncrease = { setInflateBufferBytes(nextOption(INFLATE_BUFFER_OPTIONS, settings.inflateBufferBytes)) },
        )
        SectionTitle("다운로드")
        SettingToggleRow(
            checked = settings.createCollectionSubfolder,
            onCheckedChange = setCreateCollectionSubfolder,
            label = "필요하면 컬렉션 이름의 하위 폴더 만들기",
        )
        SettingToggleRow(
            checked = settings.asciiFilenames,
            onCheckedChange = setAsciiFilenames,
            label = "파일명을 ASCII로 변환",
        )
        SettingToggleRow(
            checked = settings.autoTryCollectionPasswords,
            onCheckedChange = setAutoTryCollectionPasswords,
            label = "저장한 컬렉션 비밀번호 자동 시도",
        )
        OutlinedTextField(
            value = passwordListText,
            onValueChange = { value ->
                passwordListText = value
                setCollectionPasswordList(value.lines())
            },
            label = { Text("컬렉션 비밀번호 목록 (최대 10개)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        SettingStepper(
            title = "다운로드 대역폭",
            description = "0은 제한 없음",
            value = settings.downloadBandwidthMiBps,
            suffix = "MiB/s",
            onDecrease = { setBandwidth(settings.downloadBandwidthMiBps - 1) },
            onIncrease = { setBandwidth(settings.downloadBandwidthMiBps + 1) },
        )
        SettingStepper(
            title = "다운로드 재시도",
            description = "세그먼트별 네트워크 오류 재시도 횟수",
            value = settings.downloadMaxRetries,
            suffix = "회",
            onDecrease = { setDownloadRetries(settings.downloadMaxRetries - 1) },
            onIncrease = { setDownloadRetries(settings.downloadMaxRetries + 1) },
        )
        SectionTitle("업로드")
        SettingStepper(
            title = "업로드 대역폭",
            description = "0은 제한 없음",
            value = settings.uploadBandwidthMiBps,
            suffix = "MiB/s",
            onDecrease = { setUploadBandwidth(settings.uploadBandwidthMiBps - 1) },
            onIncrease = { setUploadBandwidth(settings.uploadBandwidthMiBps + 1) },
        )
        SettingStepper(
            title = "업로드 재시도",
            description = "세그먼트별 네트워크 오류 재시도 횟수",
            value = settings.uploadMaxRetries,
            suffix = "회",
            onDecrease = { setUploadRetries(settings.uploadMaxRetries - 1) },
            onIncrease = { setUploadRetries(settings.uploadMaxRetries + 1) },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SettingToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}

@Composable
private fun SettingStepper(
    title: String,
    description: String,
    value: Int,
    suffix: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDecrease) {
                Icon(Icons.Outlined.Remove, contentDescription = "감소")
            }
            Text("$value $suffix", modifier = Modifier.padding(horizontal = 12.dp))
            IconButton(onClick = onIncrease) {
                Icon(Icons.Outlined.Add, contentDescription = "증가")
            }
        }
    }
}