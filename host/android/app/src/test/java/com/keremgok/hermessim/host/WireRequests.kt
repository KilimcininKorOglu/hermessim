package com.keremgok.hermessim.host

import com.keremgok.hermessim.wire.Request

/** A request with id 1 made [ageMs] ago, filled by [fill]: the tests' way to build one. */
fun request(ageMs: Int = 0, fill: Request.Builder.() -> Unit): Request = Request.newBuilder().setId(1).setAgeMs(ageMs).apply(fill).build()
