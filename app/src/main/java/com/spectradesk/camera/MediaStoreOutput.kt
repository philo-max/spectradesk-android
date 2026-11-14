package com.spectradesk.camera

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.video.MediaStoreOutputOptions

/**
 * 把照片和视频写进系统图库。
 *
 * ## 为什么不写应用私有目录
 *
 * 之前照片写在 `cacheDir` 里再用 FileProvider 分享出去，系统随时会清掉，
 * 用户拍完第二天就找不到了。相机应用拍的东西应该出现在相册里 ——
 * 这是用户的预期，也是"能用"和"能玩"的区别。
 *
 * ## 为什么要分版本
 *
 * Android 10（API 29）起有 scoped storage，`MediaStore` 的
 * `RELATIVE_PATH` 才存在；API 28 及以下要靠 `DATA` 列指定绝对路径。
 * 两条路都写，是为了 minSdk 26 也能跑。
 */
object MediaStoreOutput {

    private const val ALBUM = "LightYear"
    private const val PHOTO_MIME = "image/jpeg"
    private const val VIDEO_MIME = "video/mp4"

    /** 照片的 ContentValues。 */
    fun photoContentValues(displayName: String): ContentValues =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, PHOTO_MIME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

    /** 录像的输出目标。 */
    fun mediaStore(
        resolver: android.content.ContentResolver,
        displayName: String,
    ): MediaStoreOutputOptions {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, VIDEO_MIME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$ALBUM",
                )
            }
        }
        return MediaStoreOutputOptions.Builder(
            resolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).setContentValues(values).build()
    }

    /**
     * 照片输出目标。
     *
     * ## 为什么是 OutputStream 而不是 MediaStore 的 uri
     *
     * 曾经先 `resolver.insert()` 插一行占位、再把 uri 交给 CameraX，结果
     * CameraX 内部（`JpegBytes2Disk.copyFileToMediaStore`）又自己 insert 一次，
     * 撞上同名条目直接抛异常，照片存不下来。
     *
     * 改成：我们自己 insert 拿 uri，然后用 `OutputStream` 让 CameraX 写字节。
     * 这样只插一次、只写一次，没有第二次 insert。
     *
     * 返回 null 表示插不进去（权限或 MediaStore 异常），调用方要如实报错。
     */
    fun openPhotoStream(
        resolver: android.content.ContentResolver,
        displayName: String,
    ): PhotoTarget? {
        val values = photoContentValues(displayName)
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        val stream = runCatching { resolver.openOutputStream(uri) }.getOrNull()
        if (stream == null) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return PhotoTarget(uri, stream)
    }

    /** 一张照片的写入目标。 */
    class PhotoTarget(
        val uri: Uri,
        val stream: java.io.OutputStream,
    ) : java.io.Closeable {
        override fun close() {
            runCatching { stream.close() }
        }
    }

    /**
     * 照片写完后把 IS_PENDING 清掉，否则相册里看不到。
     */
    fun publishPhoto(resolver: android.content.ContentResolver, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, values, null, null)
        }
    }

    fun deletePhoto(resolver: android.content.ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }
}
