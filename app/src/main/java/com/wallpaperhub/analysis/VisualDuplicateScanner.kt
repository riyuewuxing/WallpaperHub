package com.wallpaperhub.analysis

import com.wallpaperhub.data.db.MediaAnalysisEntity
import com.wallpaperhub.data.db.WallpaperEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Visual duplicate discovery over cached media_analysis fingerprints. */
class VisualDuplicateScanner(
    private val extractor: MediaSignalExtractor,
) {
    data class Group(
        val fingerprint: String,
        val kind: DuplicateKind,
        val wallpaperIds: List<String>,
        val similarity: Double,
        val totalBytes: Long,
    )

    data class ScanResult(
        val groups: List<Group>,
        val analysisRows: List<MediaAnalysisEntity>,
        val analyzed: Int,
        val reused: Int,
        val failed: Int,
    )

    suspend fun scan(
        wallpapers: List<WallpaperEntity>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): ScanResult = withContext(Dispatchers.Default) {
        val analysis = extractor.scan(wallpapers, requireVisualFingerprint = true, onProgress = onProgress)
        val rowsById = analysis.rows.associateBy { it.wallpaperId }
        val members = wallpapers.mapNotNull { entity ->
            val row = rowsById[entity.id] ?: return@mapNotNull null
            val hashes = MediaDigest.decodeHashSequence(row.visualHashSequence)
            if (hashes.isEmpty()) return@mapNotNull null
            DuplicateMember(
                wallpaperId = entity.id,
                sha256 = row.sha256,
                visualHashes = hashes,
                width = row.width ?: entity.width,
                height = row.height ?: entity.height,
                durationMs = row.durationMs ?: entity.duration,
                qualityScore = row.qualityScore,
                fileSizeBytes = row.sourceSizeBytes.coerceAtLeast(0L),
                inPlaylist = entity.inPlaylist,
                isFavorite = entity.isFavorite,
                cropRevision = entity.cropRevision,
                addedAt = entity.addedAt,
            )
        }
        if (members.size < 2) {
            return@withContext ScanResult(emptyList(), analysis.rows, analysis.analyzed, analysis.reused, analysis.failed)
        }

        val parent = IntArray(members.size) { it }
        fun find(start: Int): Int {
            var x = start
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        val buckets = HashMap<Int, MutableList<Int>>()
        members.forEachIndexed { index, member ->
            val hash = member.visualHashes[member.visualHashes.size / 2]
            for (band in 0 until 8) {
                val byteValue = ((hash ushr (band * 8)) and 0xffL).toInt()
                val key = (band shl 8) or byteValue
                buckets.getOrPut(key) { mutableListOf() }.add(index)
            }
        }

        val seenPairs = HashSet<Long>()
        val highEdges = mutableListOf<Triple<Int, Int, Double>>()
        val possibleEdges = mutableListOf<Triple<Int, Int, Double>>()
        buckets.values.forEach { indices ->
            if (indices.size < 2) return@forEach
            for (left in 0 until indices.lastIndex) {
                val aIndex = indices[left]
                for (right in left + 1 until indices.size) {
                    val bIndex = indices[right]
                    val low = minOf(aIndex, bIndex)
                    val high = maxOf(aIndex, bIndex)
                    val pairKey = (low.toLong() shl 32) or (high.toLong() and 0xffffffffL)
                    if (!seenPairs.add(pairKey)) continue
                    val a = members[aIndex]
                    val b = members[bIndex]
                    val kind = DuplicatePolicy.classify(a, b)
                    if (kind == DuplicateKind.EXACT || kind == DuplicateKind.NONE) continue
                    val similarity = MediaDigest.sequenceSimilarity(a.visualHashes, b.visualHashes) ?: continue
                    if (kind == DuplicateKind.VISUAL_HIGH) {
                        union(aIndex, bIndex)
                        highEdges += Triple(aIndex, bIndex, similarity)
                    } else if (kind == DuplicateKind.VISUAL_POSSIBLE) {
                        possibleEdges += Triple(aIndex, bIndex, similarity)
                    }
                }
            }
        }

        val highComponents = members.indices.groupBy { find(it) }.values.filter { it.size > 1 }
        val highMemberPairs = HashSet<Long>()
        val groups = mutableListOf<Group>()
        highComponents.forEach { component ->
            val ids = component.map { members[it].wallpaperId }.sorted()
            val componentSet = component.toHashSet()
            val similarities = highEdges.asSequence()
                .filter { it.first in componentSet && it.second in componentSet }
                .map { it.third }
                .toList()
            for (i in component.indices) {
                for (j in i + 1 until component.size) {
                    val low = minOf(component[i], component[j])
                    val high = maxOf(component[i], component[j])
                    highMemberPairs += (low.toLong() shl 32) or (high.toLong() and 0xffffffffL)
                }
            }
            groups += Group(
                fingerprint = "visual-high-${Integer.toHexString(ids.joinToString("|").hashCode())}",
                kind = DuplicateKind.VISUAL_HIGH,
                wallpaperIds = ids,
                similarity = similarities.minOrNull() ?: 0.94,
                totalBytes = ids.sumOf { rowsById[it]?.sourceSizeBytes?.coerceAtLeast(0L) ?: 0L },
            )
        }

        possibleEdges
            .sortedByDescending { it.third }
            .forEach { edge ->
                val low = minOf(edge.first, edge.second)
                val high = maxOf(edge.first, edge.second)
                val pairKey = (low.toLong() shl 32) or (high.toLong() and 0xffffffffL)
                if (pairKey in highMemberPairs) return@forEach
                val ids = listOf(members[edge.first].wallpaperId, members[edge.second].wallpaperId).sorted()
                groups += Group(
                    fingerprint = "visual-possible-${Integer.toHexString(ids.joinToString("|").hashCode())}",
                    kind = DuplicateKind.VISUAL_POSSIBLE,
                    wallpaperIds = ids,
                    similarity = edge.third,
                    totalBytes = ids.sumOf { rowsById[it]?.sourceSizeBytes?.coerceAtLeast(0L) ?: 0L },
                )
            }

        ScanResult(
            groups = groups
                .distinctBy { it.kind to it.wallpaperIds }
                .sortedWith(compareBy<Group> { if (it.kind == DuplicateKind.VISUAL_HIGH) 0 else 1 }.thenByDescending { it.similarity }.thenByDescending { it.totalBytes }),
            analysisRows = analysis.rows,
            analyzed = analysis.analyzed,
            reused = analysis.reused,
            failed = analysis.failed,
        )
    }
}
