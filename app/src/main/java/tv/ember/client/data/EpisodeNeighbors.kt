package tv.ember.client.data

data class EpisodeNeighbors(val previous: VideoItem?=null,val next: VideoItem?=null) {
    companion object {
        fun from(current: VideoItem,items: List<VideoItem>): EpisodeNeighbors {
            if(current.type!="Episode" || current.seriesId.isBlank()) return EpisodeNeighbors()
            val episodes=items.filter { it.type=="Episode" && it.seriesId==current.seriesId }
            val index=episodes.indexOfFirst { it.id==current.id }
            return if(index<0) EpisodeNeighbors() else EpisodeNeighbors(episodes.getOrNull(index-1),episodes.getOrNull(index+1))
        }
    }
}
