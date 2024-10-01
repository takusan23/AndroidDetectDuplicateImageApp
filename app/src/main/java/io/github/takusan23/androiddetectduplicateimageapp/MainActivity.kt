package io.github.takusan23.androiddetectduplicateimageapp

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.takusan23.androiddetectduplicateimageapp.ui.theme.AndroidDetectDuplicateImageAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidDetectDuplicateImageAppTheme {
                MainScreen()
            }
        }
    }
}

/** Uri の画像ハッシュ値データクラス */
data class ImageHashData(
    val uri: Uri,
    val aHash: ULong,
    val dHash: ULong
)

/**
 * 重複しているかも？画像のデータクラス
 *
 * @param from 元画像
 * @param duplicateImageList 似ている画像
 */
data class MaybeDuplicateImageData(
    val from: Uri,
    val duplicateImageList: List<Uri>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 権限をゲットする
    val permissionRequest = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGrented ->
            if (isGrented) {
                Toast.makeText(context, "権限を付与しました", Toast.LENGTH_SHORT).show()
            }
        }
    )

    val imageCount = remember { mutableIntStateOf(0) }
    val processCount = remember { mutableIntStateOf(0) }
    val maybeDuplicateImageData = remember { mutableStateOf(emptyList<MaybeDuplicateImageData>()) }

    // TODO 本当は ViewModel でやるべきです
    fun search() {
        scope.launch(Dispatchers.Default) {
            // MediaStore で画像一覧を問い合わせる
            // 権限が必要です
            val uriList = ImageTool.queryAllImageUriList(context)
            imageCount.intValue = uriList.size

            // 処理を開始
            // 並列にするなど改善の余地あり
            val imageHashList = uriList.map { uri ->
                val bitmap = ImageTool.loadBitmap(context, uri)!!
                val aHash = ImageTool.calcAHash(bitmap)
                val dHash = ImageTool.calcDHash(bitmap)
                processCount.intValue++
                ImageHashData(uri, aHash, dHash)
            }

            // しきい値
            val threshold = 0.95f
            // ImageHashList を可変長配列に。これは重複している画像が出てきら消すことで、後半になるにつれ走査回数が減るよう
            val maybeDuplicateDropImageHashList = imageHashList.toMutableList()
            // 重複している、似ている画像を探す
            imageHashList.forEach { current ->
                // 自分以外
                val withoutTargetList = maybeDuplicateDropImageHashList.filter { it.uri != current.uri }
                val maybeFromAHash = withoutTargetList.filter { threshold < ImageTool.compare(it.aHash, current.aHash) }
                val maybeFromDHash = withoutTargetList.filter { threshold < ImageTool.compare(it.dHash, current.dHash) }
                // 結果をいれる
                // aHash か dHash で重複していない場合は結果に入れない
                val totalResult = (maybeFromAHash.map { it.uri } + maybeFromDHash.map { it.uri }).distinct()
                if (totalResult.isNotEmpty()) {
                    maybeDuplicateImageData.value += MaybeDuplicateImageData(
                        from = current.uri,
                        duplicateImageList = totalResult
                    )
                }
                // 1回重複していることが分かったらもう消す（2回目以降検索にかけない）
                maybeDuplicateDropImageHashList.removeAll(maybeFromAHash)
                maybeDuplicateDropImageHashList.removeAll(maybeFromDHash)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(text = stringResource(id = R.string.app_name)) }) },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {

            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionRequest.launch(android.Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    permissionRequest.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }) {
                Text(text = "権限を付与")
            }

            Button(onClick = { search() }) {
                Text(text = "処理を開始")
            }

            Text(text = "総画像数 ${imageCount.intValue} / 処理済み画像数 ${processCount.intValue} / 重複の可能性がある画像の数 ${maybeDuplicateImageData.value.size}")

            LazyColumn {
                items(maybeDuplicateImageData.value) { maybeDuplicate ->

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UriImagePreview(
                            modifier = Modifier.requiredSize(200.dp),
                            uri = maybeDuplicate.from
                        )
                        Text(text = maybeDuplicate.from.toString())
                    }

                    LazyRow {
                        items(maybeDuplicate.duplicateImageList) { maybeUri ->
                            UriImagePreview(
                                modifier = Modifier.requiredSize(200.dp),
                                uri = maybeUri
                            )
                        }
                    }

                    HorizontalDivider()
                }
            }
        }
    }
}

/** Uri の画像を表示する */
@Composable
private fun UriImagePreview(
    modifier: Modifier = Modifier,
    uri: Uri
) {
    val context = LocalContext.current
    val image = remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(key1 = uri) {
        // TODO 自分で Bitmap を読み込むのではなく、Glide や Coil を使うべきです
        image.value = ImageTool.loadBitmap(context, uri)?.asImageBitmap()
    }

    if (image.value != null) {
        Image(
            modifier = modifier,
            bitmap = image.value!!,
            contentDescription = null
        )
    } else {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}