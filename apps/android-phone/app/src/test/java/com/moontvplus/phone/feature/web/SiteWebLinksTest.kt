package com.moontvplus.phone.feature.web

import com.moontvplus.phone.core.network.ServerAddress
import com.moontvplus.ui.model.WebPage
import org.junit.Assert.assertEquals
import org.junit.Test

class SiteWebLinksTest {

    @Test
    fun rootDeployment() {
        val base = ServerAddress.parse("https://tv.example.com")!!
        assertEquals("https://tv.example.com/live", SiteWebLinks.url(base, WebPage("live", "电视直播")))
        assertEquals("https://tv.example.com/", SiteWebLinks.url(base, WebPage("", "网页版")))
    }

    @Test
    fun subPathDeploymentKeepsPrefix() {
        val base = ServerAddress.parse("https://tv.example.com/moontv")!!
        assertEquals("https://tv.example.com/moontv/live", SiteWebLinks.url(base, WebPage("live", "电视直播")))
        assertEquals("https://tv.example.com/moontv/", SiteWebLinks.url(base, WebPage("", "网页版")))
    }
}
