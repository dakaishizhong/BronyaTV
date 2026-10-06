package tv.ember.client.cache

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Only the main progressive video is cached. Subtitles and manifests keep their own data source. */
@UnstableApi
class DiskPlaybackDataSource(
    private val videoUrl: String, private val cachedFactory: DataSource.Factory,
    private val upstreamFactory: DataSource.Factory, private val prefetch: DiskPrefetcher
) : DataSource {
    private var delegate: DataSource? = null
    private var main = false
    private var position = 0L
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener; delegate?.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        main = dataSpec.uri.toString() == videoUrl && dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET
        position = dataSpec.position
        if (main) prefetch.seek(position)
        val source = (if (main) cachedFactory else upstreamFactory).createDataSource()
        delegate = source; listeners.forEach(source::addTransferListener)
        return source.open(if (main) dataSpec.buildUpon().setKey(prefetch.key).build() else dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = requireNotNull(delegate).read(buffer, offset, length)
        if (main && count > 0) { position += count; prefetch.advance(position) }
        return count
    }
    override fun getUri(): Uri? = if(main) Uri.parse(videoUrl) else delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
    override fun close() { delegate?.close(); delegate = null }
}
