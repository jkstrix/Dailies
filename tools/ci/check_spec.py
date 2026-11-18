#!/usr/bin/env python3
"""Checks the design documents (plan item 4.2). Python 3, standard library only.

    python tools/ci/check_spec.py [--list]

It checks, in design/dailies-spec.html and design/create-and-aeronautics.html:

- each document is well formed: every tag that is opened is closed, in order, and nothing is closed that is not open;
- no two elements in one document share an id;
- every link to an anchor (#name, or other-document.html#name between the two) names an id that exists, and every
  link to a local file names one that exists;
- every requirement id is defined once: a row of a requirement table (<tr><td>REC-19</td>...) or a bold label that
  starts with the id and a full stop (<b>PAGE-1. Live:</b> in chapter XI, <b>CFG-7.</b> in chapter XIII);
- every requirement id cited anywhere in either document, chapter XIV's test steps included, is defined. A list
  such as "REC-1, 10, 12" or "MOD-1 to 4" cites each id it names; a range needs both its ends to exist.

It prints each problem with its file and line, and exits with 1 if there is any, 0 if none.
--list also prints every id it found cited, for checking the checker.
"""

import html.parser
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
DOCUMENTS = ["design/dailies-spec.html", "design/create-and-aeronautics.html"]

# Elements that have no end tag
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"}
# Words shaped like requirement ids that are not requirements
NOT_REQUIREMENTS = {"ISO", "SHA", "UTF", "AV", "MP", "CC", "BY", "LGPL", "GPL", "BSD", "WTFPL"}

ID_SHAPE = re.compile(r"^([A-Z][A-Z]+)-(\d+)$")
# An id and the numbers that follow it in a list: "REC-1, 10, 12 and 20", "MOD-1 to 4", "MOM-28, 32 to 34"
CITATION = re.compile(r"\b([A-Z][A-Z]+)-(\d+)\b((?:\s*(?:,|\bto\b|\band\b|\bor\b)\s*\d+\b(?![-.,]?\d)(?!\s*(?:%|×|x\b|(?!to\b|and\b|or\b)[a-z])))*)")
LIST_PART = re.compile(r"(,|\bto\b|\band\b|\bor\b)\s*(\d+)")


class Document(html.parser.HTMLParser):
    def __init__(self, path):
        super().__init__(convert_charrefs=True)
        self.path = path
        self.problems = []
        self.stack = []  # (tag, line)
        self.ids = {}  # id -> line
        self.links = []  # (href, line)
        self.definitions = []  # (requirement id, line)
        self.text = []  # (text, line), with the definitions themselves left out
        # Collecting the first cell of a table row, and bold text
        self.row_state = None  # None, "row" (a <tr> just opened), "cell" (in its first <td>), "done"
        self.capture = []
        self.capture_line = 0
        self.bold = None  # the text of the <b> being read, or None
        self.bold_line = 0

    def problem(self, line, message):
        self.problems.append((self.path, line, message))

    def handle_starttag(self, tag, attrs):
        line = self.getpos()[0]
        attrs = dict(attrs)
        if attrs.get("id") is not None:
            if attrs["id"] in self.ids:
                self.problem(line, "id \"%s\" is used twice (first on line %d)" % (attrs["id"], self.ids[attrs["id"]]))
            else:
                self.ids[attrs["id"]] = line
        if tag == "a" and attrs.get("href") is not None:
            self.links.append((attrs["href"], line))
        if tag == "tr":
            self.row_state = "row"
        elif tag == "td" and self.row_state == "row":
            self.row_state = "cell"
            self.capture, self.capture_line = [], line
        elif self.row_state == "row":
            self.row_state = "done"
        if tag == "b":
            self.bold, self.bold_line = [], line
        if tag not in VOID:
            self.stack.append((tag, line))

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in VOID and self.stack and self.stack[-1][0] == tag:
            self.stack.pop()

    def handle_endtag(self, tag):
        line = self.getpos()[0]
        if tag == "td" and self.row_state == "cell":
            self.row_state = "done"
            text = "".join(self.capture).strip()
            if ID_SHAPE.match(text):
                self.definitions.append((text, self.capture_line))
            else:
                self.text.append((text, self.capture_line))
        if tag == "b" and self.bold is not None:
            text = "".join(self.bold).strip()
            self.bold = None
            match = re.match(r"^([A-Z][A-Z]+-\d+)\.", text)
            if match:
                self.definitions.append((match.group(1), self.bold_line))
                text = text[len(match.group(0)):]
            if self.row_state == "cell":
                self.capture.append(text)
            else:
                self.text.append((text, self.bold_line))
        if tag in VOID:
            return
        if not self.stack:
            self.problem(line, "</%s> closes nothing" % tag)
            return
        if self.stack[-1][0] == tag:
            self.stack.pop()
            return
        open_tags = [t for t, _ in self.stack]
        if tag in open_tags:
            # Everything opened after the matching tag was left open
            while self.stack[-1][0] != tag:
                left, left_line = self.stack.pop()
                self.problem(left_line, "<%s> is not closed before </%s> on line %d" % (left, tag, line))
            self.stack.pop()
        else:
            self.problem(line, "</%s> closes nothing (the innermost open element is <%s> from line %d)"
                         % (tag, self.stack[-1][0], self.stack[-1][1]))

    def handle_data(self, data):
        line = self.getpos()[0]
        if self.bold is not None:
            self.bold.append(data)
        elif self.row_state == "cell":
            self.capture.append(data)
        else:
            self.text.append((data, line))

    def finish(self):
        self.close()
        for tag, line in self.stack:
            if tag not in ("html", "body", "head"):
                self.problem(line, "<%s> is never closed" % tag)


def citations(text):
    """Yields (id, is the start of a range, the end of that range or None) for every id the text cites."""
    for match in CITATION.finditer(text):
        prefix, first, rest = match.group(1), int(match.group(2)), match.group(3)
        if prefix in NOT_REQUIREMENTS:
            continue
        numbers = [("", first)] + [(word, int(number)) for word, number in LIST_PART.findall(rest)]
        for index, (word, number) in enumerate(numbers):
            following = numbers[index + 1] if index + 1 < len(numbers) else None
            if word == "to":
                continue  # the end of a range, checked with its start
            if following and following[0] == "to":
                yield "%s-%d" % (prefix, number), "%s-%d" % (prefix, following[1])
            else:
                yield "%s-%d" % (prefix, number), None


def main():
    show = "--list" in sys.argv[1:]
    documents = {}
    for name in DOCUMENTS:
        path = os.path.join(ROOT, name)
        with open(path, encoding="utf-8") as handle:
            source = handle.read()
        document = Document(name)
        document.feed(source)
        document.finish()
        documents[os.path.basename(name)] = document

    problems = []
    defined = {}
    for document in documents.values():
        problems += document.problems
        for requirement, line in document.definitions:
            if requirement in defined:
                where = defined[requirement]
                problems.append((document.path, line, "requirement %s is defined twice (also %s line %d)"
                                 % (requirement, where[0], where[1])))
            else:
                defined[requirement] = (document.path, line)

    for document in documents.values():
        folder = os.path.dirname(os.path.join(ROOT, document.path))
        for href, line in document.links:
            if re.match(r"^[a-z]+:", href):
                continue  # https:, mailto: and the like are not checked
            target, _, anchor = href.partition("#")
            if target:
                if not os.path.exists(os.path.join(folder, target)):
                    problems.append((document.path, line, "link to \"%s\": no such file" % href))
                    continue
                other = documents.get(os.path.basename(target))
            else:
                other = document
            if anchor and other is not None and anchor not in other.ids:
                problems.append((document.path, line, "link to \"%s\": no element has id \"%s\"" % (href, anchor)))

        cited = []
        for text, line in document.text:
            for requirement, range_end in citations(text):
                cited.append((requirement, range_end, line))
                for one in (requirement, range_end):
                    if one is not None and one not in defined:
                        problems.append((document.path, line, "cites %s, which is not a requirement"
                                         % (one if range_end is None else "%s (in %s to %s)"
                                            % (one, requirement, range_end))))
                if range_end is not None:
                    if int(range_end.split("-")[1]) <= int(requirement.split("-")[1]):
                        problems.append((document.path, line, "range %s to %s runs backwards" % (requirement, range_end)))
        if show:
            for requirement, range_end, line in cited:
                print("%s:%d: %s%s" % (document.path, line, requirement, "" if range_end is None else " to " + range_end))

    for path, line, message in sorted(problems):
        print("%s:%d: %s" % (path, line, message))
    total = len(defined)
    if problems:
        print("check_spec: %d problem%s (%d requirements)" % (len(problems), "" if len(problems) == 1 else "s", total))
        return 1
    print("check_spec: no problems (%d requirements, %d documents)" % (total, len(documents)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
