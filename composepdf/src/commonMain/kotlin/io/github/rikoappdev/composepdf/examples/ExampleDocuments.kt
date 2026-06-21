package io.github.rikoappdev.composepdf.examples

import io.github.rikoappdev.composepdf.Color
import io.github.rikoappdev.composepdf.ContainerScope
import io.github.rikoappdev.composepdf.FontWeight
import io.github.rikoappdev.composepdf.PageConfig
import io.github.rikoappdev.composepdf.PdfColor
import io.github.rikoappdev.composepdf.PdfColumn
import io.github.rikoappdev.composepdf.PdfDocumentSpec
import io.github.rikoappdev.composepdf.PhotoFit
import io.github.rikoappdev.composepdf.TextAlign
import io.github.rikoappdev.composepdf.TextStyle
import io.github.rikoappdev.composepdf.dp
import io.github.rikoappdev.composepdf.pdfDocument
import io.github.rikoappdev.composepdf.sp

/**
 * A small gallery of ready-made example documents that exercise the compose-pdf layout engine
 * across a spread of everyday document types: an invoice, a business letter, a price list, a
 * project status report, a long auto-paginating ledger, and a photo gallery.
 *
 * **All data here is invented, generic placeholder content** — the classic Contoso / Northwind /
 * Fabrikam fictitious companies, "Jane Doe"-style people, and reserved `.example` addresses.
 * Nothing is tied to any real person, company, or product; these exist purely to demonstrate
 * the library's capabilities (text flow, weighted rows, nested boxes with rounded corners and
 * backgrounds, tables with zebra striping / totals / repeating headers, automatic pagination,
 * page numbers, and image layout with [PhotoFit]).
 *
 * The JVM test `GalleryExportTest` renders each builder below to a `.pdf` plus a `.png` preview
 * under `samples/` in the repository, and the README links to them.
 */
object ExampleDocuments {

    // ---- Shared palette -------------------------------------------------------------------
    private val ink = Color(0xFF1F2933)
    private val muted = Color(0xFF6B7280)
    private val accent = Color(0xFF2563EB)
    private val hairline = Color(0xFFE5E7EB)
    private val tableHead = Color(0xFF111827)
    private val zebra = Color(0xFFF3F4F6)
    private val totalBg = Color(0xFFE5E7EB)

    // ---- Shared text styles ---------------------------------------------------------------
    private val h1 = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink)
    private val h2 = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink)
    private val body = TextStyle(fontSize = 10.sp, color = ink, lineHeightMultiple = 1.45)
    private val small = TextStyle(fontSize = 9.sp, color = muted)
    private val smallInk = TextStyle(fontSize = 9.sp, color = ink)

    // ---- Shared table styles --------------------------------------------------------------
    private val th = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = PdfColor.White)
    private val td = TextStyle(fontSize = 9.sp, color = ink)
    private val tdTotal = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ink)

    /** Applies the gallery's standard table styling so every example looks consistent. */
    private fun ContainerScope.styledTable(
        columns: List<PdfColumn>,
        repeatHeader: Boolean = true,
        build: io.github.rikoappdev.composepdf.TableScope.() -> Unit,
    ) = table(
        columns = columns,
        headerStyle = th,
        cellStyle = td,
        totalStyle = tdTotal,
        headerBackground = tableHead,
        totalBackground = totalBg,
        zebra = zebra,
        gridColor = hairline,
        cellPaddingHorizontal = 8.dp,
        cellPaddingVertical = 5.dp,
        repeatHeader = repeatHeader,
        build = build,
    )

    // =======================================================================================
    // 1) Invoice
    // =======================================================================================
    fun invoice(): PdfDocumentSpec = pdfDocument(PageConfig(margin = 40.dp, pageNumbers = false)) {
        // Brand on the left, big "INVOICE" + metadata on the right.
        row {
            cell(1.2f) {
                text("Northwind Traders", TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = accent))
                text("100 Harbor View Road", small)
                text("Lakeside, OR 97000", small)
                text("billing@northwind.example", small)
            }
            cell(1f) {
                text("INVOICE", TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = ink, align = TextAlign.End))
                spacer(6.dp)
                text("Invoice No: INV-2026-0042", small.copy(align = TextAlign.End))
                text("Issue date: 18 Jun 2026", small.copy(align = TextAlign.End))
                text("Due date: 18 Jul 2026", small.copy(align = TextAlign.End))
            }
        }

        spacer(18.dp)
        divider(hairline)
        spacer(18.dp)

        row(gap = 24.dp) {
            cell(1f) {
                text("BILL TO", small.copy(fontWeight = FontWeight.Bold, color = muted))
                spacer(3.dp)
                text("Contoso Ltd.", body.copy(fontWeight = FontWeight.Bold))
                text("250 Market Street, Suite 500", smallInk)
                text("Bayview, CA 90210", smallInk)
                text("accounts@contoso.example", smallInk)
            }
            cell(1f) {
                text("SHIP TO", small.copy(fontWeight = FontWeight.Bold, color = muted))
                spacer(3.dp)
                text("Contoso Ltd. — Warehouse 7", body.copy(fontWeight = FontWeight.Bold))
                text("18 Logistics Parkway", smallInk)
                text("Riverton, CA 90215", smallInk)
            }
        }

        spacer(18.dp)
        styledTable(
            columns = listOf(
                PdfColumn(4f, "Description"),
                PdfColumn(1f, "Qty", TextAlign.Center),
                PdfColumn(1.6f, "Unit price", TextAlign.End),
                PdfColumn(1.6f, "Amount", TextAlign.End),
            ),
        ) {
            row("Aurora wireless keyboard", "12", money(4900), money(58800))
            row("Aurora ergonomic mouse", "12", money(3250), money(39000))
            row("27\" 4K display panel", "6", money(31900), money(191400))
            row("USB-C docking station", "6", money(13900), money(83400))
            row("Braided USB-C cable (2 m)", "30", money(1190), money(35700))
            row("Annual support plan", "1", money(120000), money(120000))
            totalRow(listOf("", "", "Subtotal", money(528300)), background = PdfColor.White)
            totalRow(listOf("", "", "Tax (8.5%)", money(44906)), background = PdfColor.White)
            totalRow(listOf("", "", "Total due", money(573206)), background = totalBg)
        }

        spacer(18.dp)
        box(padding = 12.dp, background = zebra, cornerRadius = 6.dp) {
            text("Payment terms", small.copy(fontWeight = FontWeight.Bold, color = ink))
            spacer(3.dp)
            text(
                "Net 30. Please reference the invoice number with your payment. Make transfers " +
                    "payable to Northwind Traders, account 0123-4567 at Example Savings Bank.",
                small,
            )
        }
        spacer(10.dp)
        text("Thank you for your business.", small.copy(align = TextAlign.Center))
    }

    // =======================================================================================
    // 2) Business letter
    // =======================================================================================
    fun businessLetter(): PdfDocumentSpec = pdfDocument(PageConfig(margin = 56.dp, pageNumbers = false)) {
        text("Fabrikam, Inc.", TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent))
        text("88 Riverside Avenue · Elmwood, NY 10001 · hello@fabrikam.example", small)
        spacer(8.dp)
        divider(hairline)
        spacer(22.dp)

        text("18 June 2026", body)
        spacer(18.dp)
        text("Ms. Jane Doe", body)
        text("Director of Operations", body)
        text("Contoso Ltd.", body)
        text("250 Market Street, Suite 500", body)
        text("Bayview, CA 90210", body)
        spacer(18.dp)

        text("Dear Ms. Doe,", body)
        spacer(10.dp)
        text(
            "Thank you for taking the time to meet with our team last week. It was a pleasure to " +
                "learn more about Contoso's plans for the coming year, and we are excited about the " +
                "opportunity to support your operations with our products and services.",
            body,
        )
        spacer(8.dp)
        text(
            "As discussed, we have prepared an initial proposal covering equipment supply, onboarding, " +
                "and a twelve-month support plan. Our goal is to make the transition as smooth as " +
                "possible, with a dedicated account manager available to your team throughout the " +
                "engagement. We are confident that the arrangement will deliver clear value while " +
                "remaining flexible enough to adapt as your needs evolve.",
            body,
        )
        spacer(8.dp)
        text(
            "Please find the detailed quotation enclosed. We would be glad to walk you through it at " +
                "your convenience and to answer any questions that may arise. If the terms meet with " +
                "your approval, we can begin onboarding within two weeks of a signed agreement.",
            body,
        )
        spacer(16.dp)
        text("Sincerely,", body)
        spacer(30.dp)
        text("John Smith", body.copy(fontWeight = FontWeight.Bold))
        text("Account Manager, Fabrikam, Inc.", small)
    }

    // =======================================================================================
    // 3) Price list
    // =======================================================================================
    fun priceList(): PdfDocumentSpec = pdfDocument(PageConfig(margin = 40.dp)) {
        header {
            row {
                cell(1f) { text("Contoso Hardware", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink)) }
                cell(1f) { text("Price List", small.copy(align = TextAlign.End)) }
            }
            spacer(4.dp)
            divider(hairline)
        }
        footer { text("Prices in USD · Effective June 2026 · Subject to change without notice", small.copy(align = TextAlign.Center)) }

        text("Product Catalogue & Price List", h1)
        text("All items in stock unless otherwise noted. Volume discounts available on request.", small)
        spacer(16.dp)

        priceCategory(
            "Power tools",
            listOf(
                Triple("Cordless drill, 18 V", "DRL-018", money(12900)),
                Triple("Impact driver, 18 V", "IMP-018", money(14900)),
                Triple("Angle grinder, 125 mm", "GRN-125", money(8900)),
                Triple("Random orbital sander", "SND-050", money(7400)),
            ),
        )
        spacer(12.dp)
        priceCategory(
            "Hand tools",
            listOf(
                Triple("Claw hammer, 450 g", "HMR-450", money(1900)),
                Triple("Screwdriver set (12 pc)", "SDR-012", money(3200)),
                Triple("Adjustable wrench, 250 mm", "WRN-250", money(2400)),
                Triple("Tape measure, 8 m", "TPM-008", money(1500)),
            ),
        )
        spacer(12.dp)
        priceCategory(
            "Fasteners & hardware",
            listOf(
                Triple("Wood screws, 4×40 mm (200 pc)", "SCR-440", money(900)),
                Triple("Wall plugs, assorted (300 pc)", "PLG-300", money(1100)),
                Triple("Hex bolts, M8×60 (50 pc)", "BLT-860", money(1700)),
                Triple("Cable ties, 200 mm (100 pc)", "CTI-200", money(600)),
            ),
        )
    }

    private fun ContainerScope.priceCategory(title: String, items: List<Triple<String, String, String>>) {
        text(title, h2)
        spacer(6.dp)
        styledTable(
            columns = listOf(
                PdfColumn(4f, "Product"),
                PdfColumn(1.6f, "SKU", TextAlign.Center),
                PdfColumn(1.4f, "Price", TextAlign.End),
            ),
        ) {
            items.forEach { (name, sku, price) -> row(name, sku, price) }
        }
    }

    // =======================================================================================
    // 4) Project status report
    // =======================================================================================
    fun statusReport(): PdfDocumentSpec = pdfDocument(PageConfig(margin = 40.dp)) {
        header {
            row {
                cell(1f) { text("Project Phoenix", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink)) }
                cell(1f) { text("Weekly Status Report", small.copy(align = TextAlign.End)) }
            }
            spacer(4.dp)
            divider(hairline)
        }
        footer { text("Confidential — internal distribution only", small.copy(align = TextAlign.Center)) }

        text("Project Phoenix — Weekly Status Report", h1)
        text("Reporting period: 12–18 June 2026 · Prepared by Alice Johnson", small)
        spacer(14.dp)

        box(padding = 14.dp, background = zebra, cornerRadius = 6.dp) {
            text("Executive summary", h2)
            spacer(5.dp)
            text(
                "The project remains on track for the planned release at the end of the quarter. " +
                    "The core feature set is complete and in internal testing, and the team closed the " +
                    "remaining high-priority issues this week. Two medium-priority risks are being " +
                    "actively managed and are not expected to affect the timeline.",
                body,
            )
        }
        spacer(16.dp)

        row(gap = 12.dp) {
            cell(1f) { metricCard("87%", "Scope complete", accent) }
            cell(1f) { metricCard("12", "Issues closed", Color(0xFF16A34A)) }
            cell(1f) { metricCard("2", "Open risks", Color(0xFFEA580C)) }
        }
        spacer(16.dp)

        text("Milestones", h2)
        spacer(6.dp)
        styledTable(
            columns = listOf(
                PdfColumn(3f, "Milestone"),
                PdfColumn(1.6f, "Owner"),
                PdfColumn(1.4f, "Due", TextAlign.Center),
                PdfColumn(1.4f, "Status", TextAlign.Center),
            ),
        ) {
            row("Requirements sign-off", "A. Johnson", "02 May", "Done")
            row("Design system", "R. Brown", "16 May", "Done")
            row("Feature implementation", "M. Garcia", "13 Jun", "Done")
            row("Integration testing", "J. Smith", "27 Jun", "In progress")
            row("Release candidate", "A. Johnson", "04 Jul", "Planned")
            row("General availability", "Team", "11 Jul", "Planned")
        }
        spacer(16.dp)

        text("Notes & risks", h2)
        spacer(6.dp)
        text(
            "Risk 1: a third-party dependency is scheduled for a major version bump; the team has " +
                "pinned the current version and will evaluate the upgrade after release. Risk 2: one " +
                "team member is on leave during the final test week; coverage has been arranged and the " +
                "schedule includes buffer to absorb the impact.",
            body,
        )
    }

    private fun ContainerScope.metricCard(value: String, label: String, tint: PdfColor) {
        box(padding = 12.dp, border = 1.dp, borderColor = hairline, cornerRadius = 6.dp) {
            text(value, TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = tint))
            spacer(2.dp)
            text(label, small)
        }
    }

    // =======================================================================================
    // 5) Multi-page transaction ledger (showcases automatic pagination)
    // =======================================================================================
    fun transactionLedger(rowCount: Int = 90): PdfDocumentSpec = pdfDocument(PageConfig(margin = 40.dp, pageNumbers = true)) {
        header {
            text("Northwind Traders — Quarterly Transaction Ledger", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink))
            text("Q2 2026 · all amounts in USD", small)
            spacer(6.dp)
            divider(hairline)
        }
        footer { text("Generated with compose-pdf", small.copy(align = TextAlign.Center)) }

        val descriptions = listOf(
            "Office supplies", "Client invoice payment", "Cloud hosting", "Travel reimbursement",
            "Equipment purchase", "Software licence", "Consulting fee", "Utility bill",
            "Marketing spend", "Bank charges", "Shipping & handling", "Refund issued",
        )
        val categories = listOf("Operations", "Revenue", "Infrastructure", "Travel", "Capex", "Marketing")

        var runningTotal = 0L
        styledTable(
            columns = listOf(
                PdfColumn(1.4f, "Date"),
                PdfColumn(1.4f, "Reference"),
                PdfColumn(3f, "Description"),
                PdfColumn(1.6f, "Category"),
                PdfColumn(1.6f, "Amount", TextAlign.End),
            ),
            repeatHeader = true,
        ) {
            for (i in 0 until rowCount) {
                val day = (i % 28) + 1
                val month = listOf("Apr", "May", "Jun")[(i / 28) % 3]
                val date = (if (day < 10) "0$day" else "$day") + " " + month
                val reference = "TXN-" + (10_000 + i)
                val description = descriptions[i % descriptions.size]
                val category = categories[i % categories.size]
                // Deterministic pseudo-amount; every 7th row is a credit (negative).
                val magnitude = 2_500L + ((i * 9_173L) % 480_000L)
                val signed = if (i % 7 == 6) -magnitude else magnitude
                runningTotal += signed
                row(date, reference, description, category, money(signed))
            }
            totalRow(listOf("", "", "", "Net total", money(runningTotal)), background = totalBg)
        }
    }

    // =======================================================================================
    // 6) Photo gallery (showcases image layout + PhotoFit)
    // =======================================================================================
    fun photoGallery(photos: List<ByteArray>): PdfDocumentSpec = pdfDocument(PageConfig(margin = 40.dp)) {
        footer { text("compose-pdf · image layout demo", small.copy(align = TextAlign.Center)) }

        text("Photo Gallery", h1)
        text(
            "A demonstration of automatic image layout. Mixed aspect ratios are placed on a grid " +
                "without distortion — PhotoFit.Smart keeps each picture's true proportions.",
            small,
        )
        spacer(16.dp)

        text("Three per row · PhotoFit.Smart", h2)
        spacer(6.dp)
        photoGrid(photos, perRow = 3, cellHeight = 120.dp, gap = 8.dp, fit = PhotoFit.Smart)

        spacer(18.dp)
        text("Two per row · PhotoFit.Contain", h2)
        spacer(6.dp)
        photoGrid(photos.take(4), perRow = 2, cellHeight = 150.dp, gap = 10.dp, fit = PhotoFit.Contain)
    }

    // ---- Helpers --------------------------------------------------------------------------

    /** Formats an integer amount of cents as a grouped currency string, e.g. `573206` → `$5,732.06`. */
    private fun money(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val dollars = (abs / 100).toString()
        val rem = (abs % 100).toInt()
        val grouped = StringBuilder()
        val n = dollars.length
        for (i in 0 until n) {
            if (i > 0 && (n - i) % 3 == 0) grouped.append(',')
            grouped.append(dollars[i])
        }
        val cs = if (rem < 10) "0$rem" else "$rem"
        return (if (negative) "-$" else "$") + grouped.toString() + "." + cs
    }
}
