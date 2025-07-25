package com.thinkmay.thinkmay_app_beta

class Server(
    private val url: String
    private val implementation: String
) {
    var result: Int = 0

    fun getUrl(): String = url

    fun getImplementation(): String = implementation

    fun getResult(): Int = result

    fun setResult(result: Int) {
        this.result = result
    }

    override fun toString(): String {
        return "url: $url \n implementation: $implementation"
    }
}