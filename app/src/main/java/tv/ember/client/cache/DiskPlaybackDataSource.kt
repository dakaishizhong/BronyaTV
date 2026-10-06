package tv.ember.client.cache

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** The shared upstream owns cache hits and network holes, so no second cache-hole downloader can race it. */
@UnstableApi
class DiskPlaybackDataSource(private val videoUrl: String, private val upstreamFactory: DataSource.Factory,
    private val prefetch: DiskPrefetcher) : DataSource {
    private var delegate: DataSource?=null
    private var main=false
    private var position=0L
    private val listeners=mutableListOf<TransferListener>()
    override fun addTransferListener(listener: TransferListener) { listeners+=listener;delegate?.addTransferListener(listener) }
    override fun open(dataSpec: DataSpec): Long {
        main=dataSpec.uri.toString()==videoUrl && dataSpec.httpMethod==DataSpec.HTTP_METHOD_GET
        position=dataSpec.position
        if(main) prefetch.seek(position)
        val source=upstreamFactory.createDataSource();delegate=source;listeners.forEach(source::addTransferListener)
        return source.open(dataSpec)
    }
    override fun read(buffer: ByteArray,offset: Int,length: Int): Int {
        val n=requireNotNull(delegate).read(buffer,offset,length)
        if(main && n>0) { position+=n;prefetch.advance(position) }
        return n
    }
    // The upstream preserves progressive identity but resolves manifests for relative segment URLs.
    override fun getUri(): Uri?=delegate?.uri
    override fun getResponseHeaders(): Map<String,List<String>> = delegate?.responseHeaders ?: emptyMap()
    override fun close() { delegate?.close();delegate=null }
}
