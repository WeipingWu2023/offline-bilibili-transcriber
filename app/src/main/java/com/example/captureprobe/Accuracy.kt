package com.example.captureprobe

data class CerResult(val referenceCharacters: Int, val hypothesisCharacters: Int,
    val substitutions: Int, val deletions: Int, val insertions: Int) {
    val cer: Double get() = (substitutions + deletions + insertions).toDouble() / referenceCharacters
}

object Accuracy {
    fun evaluate(reference: String, hypothesis: String): CerResult {
        val r = TranscriptText.content(reference).codePoints().toArray()
        val h = TranscriptText.content(hypothesis).codePoints().toArray()
        require(r.isNotEmpty()) { "参考文本为空" }
        require(r.size <= 10000 && h.size <= 10000) { "设备 CER 比较上限 10000 字，请分片评估" }
        var cost = IntArray(h.size + 1) { it }
        var subs = IntArray(h.size + 1)
        var dels = IntArray(h.size + 1)
        var ins = IntArray(h.size + 1) { it }
        for (i in r.indices) {
            val nc = IntArray(h.size + 1); val ns = IntArray(h.size + 1)
            val nd = IntArray(h.size + 1); val ni = IntArray(h.size + 1)
            nc[0] = i + 1; nd[0] = i + 1
            for (j in h.indices) {
                val k = j + 1
                val diff = if (r[i] == h[j]) 0 else 1
                nc[k] = cost[j] + diff; ns[k] = subs[j] + diff; nd[k] = dels[j]; ni[k] = ins[j]
                if (cost[k] + 1 < nc[k]) { nc[k] = cost[k] + 1; ns[k] = subs[k]; nd[k] = dels[k] + 1; ni[k] = ins[k] }
                if (nc[j] + 1 < nc[k]) { nc[k] = nc[j] + 1; ns[k] = ns[j]; nd[k] = nd[j]; ni[k] = ni[j] + 1 }
            }
            cost = nc; subs = ns; dels = nd; ins = ni
        }
        return CerResult(r.size, h.size, subs.last(), dels.last(), ins.last())
    }
}
