/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException

/** Resource prerequisites for sharing immutable sidebar vectors, independent of cache policy. */
class SettingsSidebarResourceTest {
    private data class SidebarIcon(val page: String, val resource: Int, val name: String)

    /** The sidebar's top-level page icons share parsed vectors through SettingsIconCache. */
    private val icons = SettingsTree.topLevel.map { page ->
        val resource = approvedIconResource(page.icon)
        val name = R.drawable::class.java.fields.single { it.getInt(null) == resource }.name
        SidebarIcon(page.id, resource, name)
    }.distinctBy { it.resource }

    private val res: File
        get() = listOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull { it.isDirectory }
            ?: error("Cannot find main resources from ${File(".").absolutePath}")

    @Test fun everyTopLevelPageHasItsOwnApprovedIcon() {
        assertEquals(SettingsTree.topLevel.size, SettingsTree.topLevel.map { it.id }.toSet().size)
        assertTrue(icons.size >= 16)
    }

    @Test fun sidebarResourcesHaveOneUnqualifiedLiteralStaticVectorEach() {
        val root = res
        val names = icons.map { it.name }.toSet()
        // Values-qualified aliases are drawable variants too, even without a matching filename.
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values") }.forEach { folder ->
            folder.listFiles().orEmpty().filter { it.isFile && it.extension == "xml" }.forEach { file ->
                children(parse(file)).forEach { element ->
                    val drawable = element.tagName == "drawable" ||
                        (element.tagName == "item" && element.getAttribute("type") == "drawable")
                    assertFalse("Unexpected sidebar drawable alias in $file", drawable &&
                        element.getAttribute("name") in names)
                }
            }
        }
        icons.forEach { icon ->
            val variants = root.walkTopDown().filter { it.isFile && it.name.startsWith("${icon.name}.") }
                .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
            assertEquals("${icon.name} must have exactly one unqualified XML drawable",
                listOf("drawable/${icon.name}.xml"), variants)
            val vector = parse(File(root, variants.single()))
            assertEquals(icon.name, "vector", vector.tagName)
            assertTrue("${icon.name} needs drawable geometry", vector.getElementsByTagName("path").length > 0)
            assertLiteralStaticVector(vector, icon.name)
        }
    }

    private fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("External XML entities are forbidden") }
        return builder.parse(file).documentElement
    }

    private fun children(element: Element): List<Element> = (0 until element.childNodes.length)
        .mapNotNull { element.childNodes.item(it) as? Element }

    private fun attributes(element: Element): List<Node> = (0 until element.attributes.length)
        .map { element.attributes.item(it) }
        .filter { it.namespaceURI != XMLConstants.XMLNS_ATTRIBUTE_NS_URI }
        .sortedBy { it.localName ?: it.nodeName }

    private fun assertLiteralStaticVector(element: Element, resource: String) {
        assertTrue("$resource has unsupported element ${element.tagName}", element.tagName in allowedAttributes)
        assertTrue("$resource has a namespaced element", element.namespaceURI.isNullOrEmpty())
        attributes(element).forEach { attribute ->
            assertEquals("$resource has an unsupported attribute namespace", ANDROID_NS, attribute.namespaceURI)
            assertTrue("$resource has unsupported ${element.tagName} attribute ${attribute.localName}",
                attribute.localName in allowedAttributes.getValue(element.tagName))
            assertFalse("$resource depends on a theme/resource: ${attribute.nodeName}",
                attribute.nodeValue.contains('?') || attribute.nodeValue.contains('@'))
        }
        val descendants = children(element)
        if (element.tagName == "path") assertTrue("$resource has children inside a path", descendants.isEmpty())
        descendants.forEach { child ->
            assertFalse("$resource has a nested vector", child.tagName == "vector")
            assertLiteralStaticVector(child, resource)
        }
        for (index in 0 until element.childNodes.length) {
            val child = element.childNodes.item(index)
            when (child.nodeType) {
                Node.ELEMENT_NODE, Node.COMMENT_NODE -> Unit
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE ->
                    assertTrue("$resource has non-whitespace vector text", child.nodeValue.isBlank())
                else -> error("$resource has unsupported XML node ${child.nodeType}")
            }
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val allowedAttributes = mapOf(
            "vector" to setOf("name", "width", "height", "viewportWidth", "viewportHeight", "alpha",
                "tint", "tintMode", "autoMirrored"),
            "path" to setOf("name", "pathData", "fillColor", "fillAlpha", "fillType", "strokeColor",
                "strokeAlpha", "strokeWidth", "strokeLineCap", "strokeLineJoin", "strokeMiterLimit",
                "trimPathStart", "trimPathEnd", "trimPathOffset"),
            "group" to setOf("name", "rotation", "pivotX", "pivotY", "scaleX", "scaleY", "translateX", "translateY"),
        )
    }
}
