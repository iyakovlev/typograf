package me.deft.typograf

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlTag
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

private const val TAG_ITEM = "item"
private const val TAG_STRING = "string"

class TypografAction : AnAction() {
    companion object {
        val supportedTags = setOf(TAG_STRING, TAG_ITEM)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        if (editor == null || file == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val offset = editor.caretModel.offset
        val element =
            file.findElementAt(offset)?.parent?.let {
                findParentXmlTag(it)
            }
        e.presentation.isEnabledAndVisible = element is XmlTag && supportedTags.contains(element.name)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val caret = editor.caretModel.primaryCaret
        val offset = caret.offset

        val element =
            file.findElementAt(offset)?.parent?.let {
                findParentXmlTag(it)
            } ?: return

        val originalText = element.value.text
        val formattedText = sendToTypograf(originalText)

        WriteCommandAction.runWriteCommandAction(file.project) {
            element.value.setEscapedText(formattedText)
        }
    }

    private fun findParentXmlTag(element: PsiElement): XmlTag? {
        var current = element
        while (current !is XmlTag && current.parent != null) {
            current = current.parent
        }
        return current as? XmlTag
    }

    private fun sendToTypograf(text: String): String {
        val url = URL("http://typograf.artlebedev.ru/webservices/typograf.asmx")
        val connection = createConnection(url)

        val soapRequest =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                           xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                           xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <ProcessText xmlns="http://typograf.artlebedev.ru/webservices/">
                  <text>$text</text>
                  <entityType>3</entityType>
                  <useBr>0</useBr>
                  <useP>0</useP>
                  <maxNobr>3</maxNobr>
                  <quotA>laquo raquo</quotA>
                  <quotB>bdquo ldquo</quotB>
                </ProcessText>
              </soap:Body>
            </soap:Envelope>
            """.trimIndent()

        return try {
            val response = executeRequest(connection, soapRequest)
            parseResponse(response) ?: text
        } catch (exception: Exception) {
            text
        }
    }

    private fun executeRequest(
        connection: HttpURLConnection,
        soapRequest: String,
    ): String {
        val output = connection.outputStream
        output.write(soapRequest.toByteArray(StandardCharsets.UTF_8))
        output.flush()
        output.close()

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        return response
    }

    private fun createConnection(url: URL): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
        connection.setRequestProperty("SOAPAction", "http://typograf.artlebedev.ru/webservices/ProcessText")
        return connection
    }

    private fun parseResponse(response: String): String? {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val inputSource = InputSource(StringReader(response))
        val document = builder.parse(inputSource)

        val resultNodes = document.getElementsByTagName("ProcessTextResult")
        val result = resultNodes.item(0)?.textContent
        return result?.trim()
    }
}
