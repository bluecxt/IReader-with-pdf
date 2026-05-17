package ireader.domain.usecases.pdf

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import ireader.core.source.LocalSource
import ireader.core.source.model.ImageUrl
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.Page
import ireader.core.source.model.Text
import ireader.domain.data.repository.BookRepository
import ireader.domain.data.repository.ChapterRepository
import ireader.domain.models.entities.Book
import ireader.domain.models.entities.Chapter
import ireader.domain.storage.CacheManager
import ireader.domain.storage.StorageManager
import ireader.domain.usecases.file.FileSaver
import ireader.domain.utils.extensions.currentTimeToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Android PDF import implementation using PDFBox-Android
 * 
 * Extracts text with basic formatting (italics) and images from PDF files.
 */
actual class ImportPdf(
    private val bookRepository: BookRepository,
    private val chapterRepository: ChapterRepository,
    private val fileSaver: FileSaver,
    private val cacheManager: CacheManager,
    private val storageManager: StorageManager,
    context: Context
) {
    private val appContext: Context = context.applicationContext
    
    init {
        PDFBoxResourceLoader.init(appContext)
    }
    
    actual suspend fun parse(uris: List<ireader.domain.models.common.Uri>) = withContext(Dispatchers.IO) {
        val errors = mutableListOf<Pair<String, String>>()
        
        uris.forEach { uri ->
            try {
                importPdf(uri)
            } catch (e: Exception) {
                val filePath = uri.androidUri.path ?: uri.toString()
                errors.add(filePath to (e.message ?: "Unknown error"))
                println("Failed to import PDF $filePath: ${e.message}")
                e.printStackTrace()
            }
        }
        
        if (errors.isNotEmpty()) {
            val errorMessage = errors.joinToString("\n") { (path, error) ->
                "${File(path).name}: $error"
            }
            throw Exception("Failed to import ${errors.size} PDF file(s):\n$errorMessage")
        }
    }
    
    private suspend fun importPdf(uri: ireader.domain.models.common.Uri) {
        val tempFile = File(appContext.cacheDir, "temp_pdf_${currentTimeToLong()}.pdf")
        
        try {
            // Copy content to temp file
            fileSaver.readSource(uri).buffer().use { source ->
                FileSystem.SYSTEM.sink(tempFile.toOkioPath()).buffer().use { sink ->
                    sink.writeAll(source)
                }
            }
            
            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw Exception("Failed to read PDF file")
            }
            
            // Open PDF with PDFBox
            val document = PDDocument.load(tempFile)
            
            try {
                val pageCount = document.numberOfPages
                if (pageCount == 0) {
                    throw Exception("PDF has no pages")
                }
                
                // Extract metadata
                val info = document.documentInformation
                val author = info?.author?.takeIf { it.isNotBlank() } ?: "PDF Import"
                val subject = info?.subject?.takeIf { it.isNotBlank() } ?: "Imported from PDF ($pageCount pages)"
                
                // Extract title from filename
                val fileName = uri.androidUri.lastPathSegment?.substringAfterLast("/")
                    ?: tempFile.nameWithoutExtension
                val title = info?.title?.takeIf { it.isNotBlank() } 
                    ?: fileName.removeSuffix(".pdf").removeSuffix(".PDF")
                
                // Generate unique key
                val key = generateBookKey(title)
                bookRepository.delete(key)
                
                // Create book
                val bookId = Book(
                    title = title,
                    key = key,
                    favorite = true,
                    sourceId = LocalSource.SOURCE_ID,
                    cover = "",
                    author = author,
                    status = MangaInfo.UNKNOWN,
                    description = subject,
                    lastUpdate = currentTimeToLong()
                ).let { bookRepository.upsert(it) }
                
                // Extract chapters
                val chapters = extractChapters(document, bookId, key, pageCount)
                
                if (chapters.isEmpty()) {
                    throw Exception("No readable content could be extracted from PDF")
                }
                
                chapterRepository.insertChapters(chapters)
                println("Successfully imported PDF: $title (${chapters.size} chapters from $pageCount pages)")
                
            } finally {
                document.close()
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
    
    /**
     * Custom stripper to detect italics and bold
     */
    private class StyledTextStripper : PDFTextStripper() {
        private val output = StringBuilder()
        private var isItalic = false
        private var isBold = false

        init {
            setSortByPosition(true)
        }

        fun getStyledText(document: PDDocument, pageNum: Int): String {
            output.setLength(0)
            isItalic = false
            isBold = false
            startPage = pageNum + 1
            endPage = pageNum + 1
            writeText(document, java.io.StringWriter()) 
            return output.toString()
        }

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            if (text == null || textPositions == null) return

            for (i in textPositions.indices) {
                val pos = textPositions[i]
                val font = pos.font
                val fontDescriptor = font.fontDescriptor
                val fontName = font.name.lowercase()
                
                // Detect italics
                val nowItalic = fontName.contains("italic") || 
                                fontName.contains("oblique") ||
                                (fontDescriptor != null && fontDescriptor.isItalic)
                
                // Detect bold
                val nowBold = fontName.contains("bold") || 
                              (fontDescriptor != null && fontDescriptor.fontWeight >= 700)

                // Handle Bold Tags
                if (nowBold && !isBold) {
                    output.append("<b>")
                    isBold = true
                } else if (!nowBold && isBold) {
                    output.append("</b>")
                    isBold = false
                }

                // Handle Italic Tags
                if (nowItalic && !isItalic) {
                    output.append("<i>")
                    isItalic = true
                } else if (!nowItalic && isItalic) {
                    output.append("</i>")
                    isItalic = false
                }
                
                output.append(pos.unicode)
            }
            
            if (isItalic) {
                output.append("</i>")
                isItalic = false
            }
            if (isBold) {
                output.append("</b>")
                isBold = false
            }
            
            output.append("\n") 
        }
    }

    /**
     * Extract chapters from PDF pages
     */
    private fun extractChapters(
        document: PDDocument,
        bookId: Long,
        key: String,
        pageCount: Int
    ): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        val pagesPerChapter = calculatePagesPerChapter(pageCount)
        
        val styledStripper = StyledTextStripper()
        val imagesCacheDir = cacheManager.getCacheSubDirectory("pdf_images/$key").toFile()
        if (!imagesCacheDir.exists()) imagesCacheDir.mkdirs()
        
        var chapterIndex = 0
        var currentPageStart = 0
        
        while (currentPageStart < pageCount) {
            val pageEnd = minOf(currentPageStart + pagesPerChapter, pageCount)
            val pages = mutableListOf<Page>()
            
            for (pageNum in currentPageStart until pageEnd) {
                // 1. Extract styled text
                try {
                    val pageText = styledStripper.getStyledText(document, pageNum).trim()
                    if (pageText.isNotBlank()) {
                        splitIntoParagraphs(pageText).forEach {
                            pages.add(Text(it))
                        }
                        // Add a subtle spacer between pages
                        pages.add(Text("\n")) 
                    }
                } catch (e: Exception) {
                    println("Failed to extract text from page $pageNum: ${e.message}")
                }
                
                // 2. Extract images with high-quality rendering
                try {
                    val page = document.getPage(pageNum)
                    val resources = page.resources
                    val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(document)
                    var imageCount = 0
                    
                    for (name in resources.xObjectNames) {
                        if (resources.isImageXObject(name)) {
                            val xObject = resources.getXObject(name) as? PDImageXObject
                            xObject?.let { img ->
                                // Render the specific area of the image at 300 DPI for crystal clear quality
                                // For simplicity and best results in web novels, we render the whole page 
                                // if it's mostly an image, or just extract the high-res bitmap.
                                val bitmap = img.image 
                                
                                val imageFile = File(imagesCacheDir, "p${pageNum}_i${imageCount}.webp")
                                val outputStream = FileOutputStream(imageFile)
                                try {
                                    // Use WebP Lossless for best quality/size ratio on Android
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, outputStream)
                                    } else {
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                                    }
                                    
                                    pages.add(Text("\n")) // Spacer before
                                    pages.add(ImageUrl(imageFile.absolutePath))
                                    pages.add(Text("\n")) // Spacer after
                                    imageCount++
                                } finally {
                                    outputStream.close()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("Error extracting images from page $pageNum: ${e.message}")
                }
            }
            
            val chapterTitle = if (pageCount <= pagesPerChapter) {
                "Full Document"
            } else {
                "Pages ${currentPageStart + 1}-$pageEnd"
            }
            
            if (pages.isNotEmpty()) {
                chapters.add(
                    Chapter(
                        name = chapterTitle,
                        key = "${key}_chapter_$chapterIndex",
                        bookId = bookId,
                        content = pages,
                        number = chapterIndex.toFloat(),
                        dateUpload = currentTimeToLong()
                    )
                )
                chapterIndex++
            }
            
            currentPageStart = pageEnd
        }
        
        return chapters
    }
    
    /**
     * Split text into paragraphs more aggressively
     */
    private fun splitIntoParagraphs(text: String): List<String> {
        val lines = text.replace("\r\n", "\n").split("\n").map { it.trim() }
        val paragraphs = mutableListOf<String>()
        var currentParagraph = StringBuilder()

        for (line in lines) {
            if (line.isEmpty()) {
                if (currentParagraph.isNotEmpty()) {
                    paragraphs.add(currentParagraph.toString().trim())
                    currentParagraph = StringBuilder()
                }
                continue
            }

            if (currentParagraph.isNotEmpty()) {
                val prevText = currentParagraph.toString().trim()
                // Start a new paragraph if the previous line ends with sentence punctuation
                // or if the current line starts with an HTML tag (like <i>)
                // or if the current line starts/ends with ―― (em-dashes)
                if (prevText.endsWith(".") || prevText.endsWith("!") || prevText.endsWith("?") || 
                    line.startsWith("<") || prevText.endsWith(">") ||
                    line.startsWith("――") || line.endsWith("――") ||
                    prevText.endsWith("――")) {
                    paragraphs.add(prevText)
                    currentParagraph = StringBuilder(line)
                } else {
                    currentParagraph.append(" ").append(line)
                }
            } else {
                currentParagraph.append(line)
            }
        }
        
        if (currentParagraph.isNotEmpty()) {
            paragraphs.add(currentParagraph.toString().trim())
        }

        return paragraphs.filter { it.isNotBlank() }
    }
    
    private fun calculatePagesPerChapter(pageCount: Int): Int {
        return when {
            pageCount <= 10 -> pageCount
            pageCount <= 50 -> 5
            pageCount <= 100 -> 10
            pageCount <= 500 -> 20
            else -> 50
        }
    }
    
    private fun generateBookKey(title: String): String {
        val sanitized = title.replace(Regex("[^a-zA-Z0-9]"), "_")
        val timestamp = currentTimeToLong()
        return "pdf_${sanitized}_$timestamp"
    }
    
    actual fun getCacheSize(): String {
        return cacheManager.getCacheSize()
    }
    
    actual fun removeCache() {
        cacheManager.clearAllCache()
    }
}
