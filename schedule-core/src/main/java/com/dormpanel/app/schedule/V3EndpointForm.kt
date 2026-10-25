package com.dormpanel.app.schedule

/** Account base, bound folder and candidate folder are deliberately separate.
 * Only an explicit UI action retargets the origin; the bound path is preserved. */
internal object V3EndpointForm {
    fun retarget(folder:String,server:String):String {
        val current=WebDavClient().url(folder);val target=WebDavClient().url(server)
        return current.newBuilder().scheme(target.scheme).host(target.host).port(target.port).build().toString()
    }
}
