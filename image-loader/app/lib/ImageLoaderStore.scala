package lib.storage

import lib.ImageLoaderConfig
import com.gu.mediaservice.lib

class ImageLoaderStore(config: ImageLoaderConfig) extends lib.ImageIngestOperations(config.quarantineBucket, config.imageBucket, config.thumbnailBucket, config)
