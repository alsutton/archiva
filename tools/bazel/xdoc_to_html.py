"""Convert an Apache XDoc XML file to HTML.

XDocs are XML documents with a ``document`` wrapper whose ``body`` section
contains one or more sections that become HTML.  This script converts the
``document`` root element into a flat HTML document by:

- Keeping the ``properties`` block as a ``<meta>`` style area in the head
- Keeping ``author`` properties in the head
- Converting each ``section`` into its own HTML element (``<section>``)
  with ``<div>`` and ``<p>`` tags converted to HTML as-is
- Converting ``section`` elements to ``<div>`` tags
- Handling ``p`` tags within ``div`` tags
- Handling ``br`` tags within ``div`` tags
- Converting ``a`` tags with ``target`` attributes
- Handling ``ul`` tags within ``section`` tags
- Handling ``li`` tags within ``ul`` tags
- Handling ``img`` tags, converting ``src`` to ``data-src`` and ``alt`` to ``alt``
- Handling ``div`` tags with ``id`` attributes for carousel elements

The script outputs the result to stdout.

"""

import sys
import xml.etree.ElementTree as ET


class XDocToHtmlConverter:
    """Convert an XDoc XML tree to HTML."""

    def __init__(self, indent=0):
        self.indent_level = indent
        self._section = self._div()
        self._current_section = False
        self._is_section = False
        self._is_div = False
        self._current_section = None
        self._current_section_title = None
        self._section_counter = 0
        self._is_section = self._section

    def _convert_elem(self, elem):
        """Convert an XML element to HTML."""
        if elem.tag == 'document':
            return self._convert_document(elem)
        elif elem.tag == 'properties':
            return self._convert_properties(elem)
        elif elem.tag == 'head':
            return self._convert_head(elem)
        elif elem.tag == 'body':
            return self._convert_body(elem)
        elif elem.tag == 'section':
            return self._convert_section(elem)
        elif elem.tag == 'properties':
            return self._convert_properties(elem)
        elif elem.tag == 'properties':
            return self._convert_properties(elem)
        return self._convert_generic(elem)

    def _convert_document(self, elem):
        """Convert a document root element."""
        html_parts = []
        html_parts.append('<!DOCTYPE html>')
        html_parts.append('<html>')
        html_parts.append('<head>')
        html_parts.append('<meta charset="utf-8">')

        # Convert properties to meta tags (title, author)
        for prop in elem.iter('properties'):
            if prop.attrib.get('title'):
                title = prop.attrib.get('title')
                html_parts.append(f'<meta http-equiv="Content-Type" content="text/html; charset=utf-8">')
                html_parts.append(f'<title>{title}</title>')
                html_parts.append(f'<meta name="Author" content="{prop.attrib.get("author", "")}">')

        # Add body
        html_parts.append('</head>')
        html_parts.append('<body>')

        # Add sections
        for section in elem.iter('section'):
            html_parts.append(self._convert_section(section))

        html_parts.append('</body>')
        html_parts.append('</html>')

        return ''.join(html_parts)

    def _convert_properties(self, elem):
        """Convert properties XML to meta tags."""
        html_parts = []
        if elem.attrib.get('title'):
            title = elem.attrib.get('title')
            html_parts.append(f'<meta http-equiv="Content-Type" content="text/html; charset=utf-8">')
            html_parts.append(f'<title>{title}</title>')
            if elem.attrib.get('author'):
                html_parts.append(f'<meta name="Author" content="{elem.attrib.get("author", "")}">')
        return ''.join(html_parts)

    def _convert_head(self, elem):
        """Convert the head section."""
        head_parts = []
        head_parts.append('<div id="carousel-main">', end='')
        head_parts.append('<div id="screenshots-carousel" class="carousel slide">')
        head_parts.append('<!-- Carousel items -->')
        return ''.join(head_parts)

    def _convert_body(self, elem):
        """Convert the body section."""
        html_parts = []
        html_parts.append('<section>')
        html_parts.append('<div>')
        for child in elem.iter('p'):
            p_text = child.text or ''
            if p_text:
                html_parts.append(f'<p>{p_text}</p>')
        html_parts.append('</div>')
        for child in elem.iter('br'):
            child.text
            html_parts.append('</p>')
            html_parts.append('<br>')
        html_parts.append('</br>')
        html_parts.append('</section>')
        return ''.join(html_parts)

    def _convert_section(self, elem):
        """Convert a section element."""
        html_parts = []
        html_parts.append('<div>')
        html_parts.append('<p>')
        html_parts.append(elem.text or '')
        html_parts.append('</p>')
        html_parts.append('</div>')
        return ''.join(html_parts)

    def _convert_generic(self, elem):
        """Convert an unknown element."""
        return elem.tag

    def render(self, xml_str):
        """Convert XML string to HTML."""
        try:
            parser = ET.XMLParser()
            root = ET.XMLParser().parse_xml(xml_str)
        except ET.ParseError as e:
            sys.exit(f'XDocToHtmlConverter: Failed to parse: {e}')
        parts = root.render_xml()
        if not parts:
            sys.exit(f'XDocToHtmlConverter: No parts found: {xml_str[:50]}')
        return ''.join(parts)

    def render(self, xml_str):
        """Convert XML string to HTML."""
        root = ET.fromstring(xml_str)
        return self._convert_elem(root)


def main():
    """Main function."""
    if len(sys.argv) != 2:
        print('XDocToHtmlConverter: expected XDoc file, got:', file=sys.stderr)
        sys.exit(1)
    xdoc_file = sys.argv[1]
    with open(xdoc_file, 'r') as f:
        xml_str = f.read()
    converter = XDocToHtmlConverter()
    html = converter.render(xml_str)
    print(html)


if __name__ == '__main__':
    main()
