package com.dormpanel.phone

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class CoursePaletteTest {
    private fun channels(rgb: Int) = listOf(rgb ushr 16 and 255,rgb ushr 8 and 255,rgb and 255)
    private fun linear(rgb: Int) = channels(rgb).map { c -> (c/255.0).let { if(it<=.04045) it/12.92 else ((it+.055)/1.055).pow(2.4) } }
    private fun luminance(rgb: Int) = linear(rgb).let { .2126*it[0]+.7152*it[1]+.0722*it[2] }
    private fun lab(rgb: Int): List<Double> {
        val (r,g,b)=linear(rgb)
        val xyz=listOf((.4124564*r+.3575761*g+.1804375*b)/.95047,
            .2126729*r+.7151522*g+.072175*b,(.0193339*r+.119192*g+.9503041*b)/1.08883)
            .map { if(it>.008856) it.pow(1.0/3) else 7.787*it+16.0/116 }
        return listOf(116*xyz[1]-16,500*(xyz[0]-xyz[1]),200*(xyz[1]-xyz[2]))
    }
    @Test fun themedPaletteHasSeparationAndReadableInk() {
        assertEquals(16,CourseColors.palette.size)
        for(dark in listOf(false,true)) {
            val backgrounds=CourseColors.palette.map { CourseColors.background(it,dark) }
            val labs=backgrounds.map(::lab)
            // CIE76 is a regression floor, complemented by the physical screenshot review.
            for(a in labs.indices) for(b in a+1 until labs.size) {
                val distance=sqrt(labs[a].zip(labs[b]).sumOf { (x,y)->(x-y).pow(2) })
                assertTrue("Near-duplicate themed slots $a/$b dark=$dark: $distance",distance>=12)
            }
            val ink=luminance(if(dark) 0xf5f5f5 else 0x1e2830)
            backgrounds.forEach { color ->
                val background=luminance(color)
                assertTrue((max(ink,background)+.05)/(min(ink,background)+.05)>=4.5)
            }
        }
    }
}
