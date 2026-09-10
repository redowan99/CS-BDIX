package com.redowan

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BanglaPlexPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BanglaPlexProvider())
        registerExtractorAPI(PasteUrlExtractor())
        registerExtractorAPI(PasteTotExtractor())
        registerExtractorAPI(StreamHG())
        registerExtractorAPI(StreamHGCom())
        registerExtractorAPI(StreamHGTo())
        registerExtractorAPI(Vibuxer())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDFlixNew1())
        registerExtractorAPI(GDFlixNew2())
        registerExtractorAPI(GDFlixNew3())
        registerExtractorAPI(GDFlixNew4())
        registerExtractorAPI(GDFlixNew10())
        registerExtractorAPI(GDFlixDev())
        registerExtractorAPI(GDFlixCfd())
    }
}
