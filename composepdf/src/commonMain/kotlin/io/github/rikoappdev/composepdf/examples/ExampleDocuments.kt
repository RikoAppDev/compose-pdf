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

    // ---- Extra styles & inline vector assets (for the complex samples) --------------------
    private val cardHead = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ink)
    private val sidebarHead = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = muted)

    /** Inline Android VectorDrawable (a hex badge) — demonstrates the VectorDrawable importer. */
    private val VD_HEX_BADGE: ByteArray = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="48dp" android:height="48dp" android:viewportWidth="48" android:viewportHeight="48">
          <path android:pathData="M24 3 L43 14 L43 34 L24 45 L5 34 L5 14 Z" android:fillColor="#0F766E"/>
          <path android:pathData="M24 14 L34 20 L34 30 L24 36 L14 30 L14 20 Z" android:fillColor="#5EEAD4"/>
        </vector>
    """.trimIndent().encodeToByteArray()

    /** Inline SVG (a layered badge) — demonstrates the SVG importer (basic shapes + rounded rects). */
    private val SVG_BADGE: ByteArray = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
          <rect x="2" y="2" width="60" height="60" rx="12" fill="#111827"/>
          <circle cx="32" cy="23" r="11" fill="#22D3EE"/>
          <rect x="13" y="38" width="38" height="8" rx="4" fill="#22D3EE"/>
          <rect x="20" y="50" width="24" height="6" rx="3" fill="#64748B"/>
        </svg>
    """.trimIndent().encodeToByteArray()

    // =======================================================================================
    // 7) Field service report — flagship multi-page: repeating header/footer + page numbers,
    //    bordered record cards (dark title bar, 3-column summary with totals, task list, note,
    //    photo grid, signature), VectorDrawable logo. Cards split across pages.
    // =======================================================================================
    private data class ServiceVisit(
        val date: String,
        val summary: String,
        val technicians: List<Pair<String, String>>,
        val totalHours: String,
        val equipment: List<Pair<String, String>>,
        val parts: List<Pair<String, String>>,
        val partsTotal: String,
        val tasks: List<String>,
        val note: String,
        val photoCount: Int,
    )

    fun fieldServiceReport(photos: List<ByteArray>): PdfDocumentSpec =
        pdfDocument(PageConfig(margin = 36.dp, pageNumbers = true, repeatHeader = true)) {
            header {
                row(gap = 16.dp) {
                    cell(1f) { vector(VD_HEX_BADGE, height = 30.dp, fit = PhotoFit.Contain) }
                    cell(2f) {
                        text("Contoso Facilities", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink, align = TextAlign.End))
                        text("Service provider", small.copy(align = TextAlign.End))
                        text("dispatch@contoso.example", small.copy(align = TextAlign.End))
                    }
                    cell(2f) {
                        text("Northwind Plant 7", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink, align = TextAlign.End))
                        text("Client site", small.copy(align = TextAlign.End))
                        text("12 Industrial Way, Lakeside", small.copy(align = TextAlign.End))
                    }
                }
                spacer(8.dp)
                divider(hairline)
            }
            footer { text("Facility Maintenance Report · WO-2026-0317", small.copy(align = TextAlign.Center)) }

            text("Facility Maintenance Report", h1)
            text("Work order WO-2026-0317 · Reporting window: 8–12 June 2026", small)
            spacer(12.dp)

            serviceVisits().forEach { serviceCard(it, photos) }
        }

    private fun serviceVisits(): List<ServiceVisit> {
        val techs = listOf("Alex Rivera", "Sam Patel", "Jordan Lee", "Casey Morgan", "Drew Nguyen")
        val taskPool = listOf(
            "Inspected the air handler and replaced intake filters",
            "Lubricated conveyor bearings and checked belt tension",
            "Calibrated temperature sensors in cold storage",
            "Tested emergency lighting and replaced two ballasts",
            "Cleared the condensate drain and flushed the line",
            "Verified fire-suppression pressure gauges",
            "Tightened electrical-panel connections and logged readings",
            "Replaced worn gaskets on the pump housing",
        )
        return (0 until 7).map { i ->
            ServiceVisit(
                date = "${8 + i % 5} Jun 2026 · 08:${if (i % 2 == 0) "00" else "30"}",
                summary = "Scheduled preventive maintenance — zone ${'A' + i % 4}",
                technicians = listOf(
                    techs[i % techs.size] to "${4 + i % 3} h",
                    techs[(i + 2) % techs.size] to "${3 + i % 2} h",
                ),
                totalHours = "${7 + i % 4} h",
                equipment = listOf(
                    "Air handler AH-${10 + i}" to "${2 + i % 3} h",
                    "Conveyor C-${3 + i % 5}" to "${1 + i % 2} h",
                ),
                parts = listOf(
                    "Intake filter (set)" to money(4500),
                    "Drive belt" to money(3200),
                    "Sealant cartridge" to money(900),
                ),
                partsTotal = money(8600),
                tasks = (taskPool.drop(i % taskPool.size) + taskPool.take(i % taskPool.size)).take(4),
                note = if (i % 3 == 0) "Follow-up recommended: order a replacement bearing for the next cycle." else "",
                photoCount = i % 4,
            )
        }
    }

    private fun ContainerScope.serviceCard(v: ServiceVisit, photos: List<ByteArray>) {
        box(border = 1.dp, borderColor = Color(0xFFCBD5E1), cornerRadius = 6.dp) {
            box(padding = 8.dp, background = Color(0xFF334155), cornerRadius = 6.dp) {
                row {
                    cell(2f) { text(v.date, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PdfColor.White)) }
                    cell(3f) { text(v.summary, TextStyle(fontSize = 8.sp, color = PdfColor.White, align = TextAlign.End)) }
                }
            }
            box(padding = 12.dp) {
                row(gap = 10.dp) {
                    cell(1f) {
                        text("Technicians", cardHead)
                        spacer(3.dp)
                        v.technicians.forEach { (n, h) -> labelValueRow(n, h) }
                        divider(hairline)
                        labelValueRow("Total hours", v.totalHours, bold = true)
                    }
                    cell(1f) {
                        text("Equipment", cardHead)
                        spacer(3.dp)
                        v.equipment.forEach { (n, h) -> labelValueRow(n, h) }
                    }
                    cell(1f) {
                        text("Parts", cardHead)
                        spacer(3.dp)
                        v.parts.forEach { (n, p) -> labelValueRow(n, p) }
                        divider(hairline)
                        labelValueRow("Parts total", v.partsTotal, bold = true)
                    }
                }
                if (v.tasks.isNotEmpty()) {
                    spacer(8.dp)
                    box {
                        text("Tasks performed", cardHead)
                        spacer(3.dp)
                        v.tasks.forEach { t -> text("•  $t", smallInk) }
                    }
                }
                if (v.note.isNotBlank()) {
                    spacer(8.dp)
                    box {
                        text("Notes", cardHead)
                        spacer(3.dp)
                        text(v.note, smallInk)
                    }
                }
                if (v.photoCount > 0 && photos.isNotEmpty()) {
                    spacer(8.dp)
                    box {
                        text("Photos", cardHead)
                        spacer(3.dp)
                        photoGrid(photos.take(v.photoCount.coerceAtMost(photos.size)), perRow = 3, cellHeight = 66.dp, gap = 6.dp, fit = PhotoFit.Smart)
                    }
                }
                spacer(12.dp)
                row {
                    cell(3f) {}
                    cell(2f) {
                        spacer(26.dp)
                        divider(hairline)
                        spacer(3.dp)
                        text("Technician signature", small.copy(align = TextAlign.End))
                    }
                }
            }
        }
        spacer(10.dp)
    }

    private fun ContainerScope.labelValueRow(label: String, value: String, bold: Boolean = false) {
        val ls = if (bold) tdTotal else smallInk
        val vs = (if (bold) tdTotal else smallInk).copy(align = TextAlign.End)
        row { cell(3f) { text(label, ls) }; cell(2f) { text(value, vs) } }
    }

    // =======================================================================================
    // 8) Annual report — title + metric cards, then a large multi-page ledger with a repeating
    //    header, zebra striping, periodic subtotals and a grand total.
    // =======================================================================================
    fun annualReport(): PdfDocumentSpec = pdfDocument(PageConfig(margin = 40.dp, pageNumbers = true)) {
        header {
            row {
                cell(1f) { text("Fabrikam, Inc.", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink)) }
                cell(1f) { text("Annual Financial Report 2026", small.copy(align = TextAlign.End)) }
            }
            spacer(4.dp)
            divider(hairline)
        }
        footer { text("Fabrikam, Inc. · Confidential", small.copy(align = TextAlign.Center)) }

        text("Annual Financial Report", h1)
        text("Fiscal year ending 31 December 2026 · all amounts in USD", small)
        spacer(14.dp)

        row(gap = 12.dp) {
            cell(1f) { metricCard("\$12.8M", "Total revenue", accent) }
            cell(1f) { metricCard("\$4.1M", "Net profit", Color(0xFF16A34A)) }
            cell(1f) { metricCard("+18%", "YoY growth", Color(0xFF0EA5E9)) }
            cell(1f) { metricCard("342", "Active clients", Color(0xFFEA580C)) }
        }
        spacer(16.dp)

        text("General ledger — detailed transactions", h2)
        spacer(6.dp)
        ledgerTable(rowCount = 120)
    }

    private fun ContainerScope.ledgerTable(rowCount: Int) {
        val descriptions = listOf(
            "Client invoice payment", "Cloud hosting", "Office supplies", "Travel reimbursement",
            "Equipment purchase", "Software licence", "Consulting fee", "Utility bill",
            "Marketing spend", "Bank charges", "Shipping & handling", "Refund issued",
        )
        val categories = listOf("Revenue", "Infrastructure", "Operations", "Travel", "Capex", "Marketing")
        var running = 0L
        var block = 0L
        styledTable(
            columns = listOf(
                PdfColumn(1.3f, "Date"),
                PdfColumn(1.5f, "Reference"),
                PdfColumn(3f, "Description"),
                PdfColumn(1.5f, "Category"),
                PdfColumn(1.6f, "Amount", TextAlign.End),
            ),
            repeatHeader = true,
        ) {
            for (i in 0 until rowCount) {
                val day = (i % 28) + 1
                val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun")[(i / 28) % 6]
                val date = (if (day < 10) "0$day" else "$day") + " " + month
                val reference = "TXN-" + (100_000 + i)
                val magnitude = 1_800L + ((i * 7_919L) % 520_000L)
                val signed = if (i % 6 == 5) -magnitude else magnitude
                running += signed
                block += signed
                row(date, reference, descriptions[i % descriptions.size], categories[i % categories.size], money(signed))
                if ((i + 1) % 20 == 0) {
                    totalRow(listOf("", "", "", "Subtotal", money(block)), background = PdfColor.White)
                    block = 0
                }
            }
            totalRow(listOf("", "", "", "Net total for year", money(running)), background = totalBg)
        }
    }

    // =======================================================================================
    // 9) Product catalogue — categorized tables interleaved with photo grids, SVG brand mark.
    // =======================================================================================
    fun productCatalog(photos: List<ByteArray>): PdfDocumentSpec = pdfDocument(PageConfig(margin = 40.dp, pageNumbers = true)) {
        header {
            row(gap = 12.dp) {
                cell(1f) { vector(SVG_BADGE, height = 24.dp, fit = PhotoFit.Contain) }
                cell(5f) { text("Northwind Traders — Product Catalogue", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink, align = TextAlign.End)) }
            }
            spacer(4.dp)
            divider(hairline)
        }
        footer { text("Catalogue 2026 · prices in USD · subject to change", small.copy(align = TextAlign.Center)) }

        text("Product Catalogue", h1)
        text("Featured ranges with current pricing. Product images are illustrative.", small)
        spacer(12.dp)

        text("Featured this season", h2)
        spacer(6.dp)
        photoGrid(photos, perRow = 3, cellHeight = 110.dp, gap = 8.dp, fit = PhotoFit.Cover)
        spacer(16.dp)

        catalogCategory(
            "Workstations",
            listOf(
                listOf("WS-1100", "Aurora Mini Desktop", "6-core / 16 GB / 512 GB", "In stock", money(89900)),
                listOf("WS-1200", "Aurora Tower", "8-core / 32 GB / 1 TB", "In stock", money(139900)),
                listOf("WS-1300", "Aurora Pro Tower", "12-core / 64 GB / 2 TB", "Low", money(219900)),
                listOf("WS-1400", "Aurora Compact", "4-core / 8 GB / 256 GB", "In stock", money(59900)),
                listOf("WS-1500", "Aurora Studio", "10-core / 32 GB / 1 TB", "In stock", money(179900)),
                listOf("WS-1600", "Aurora Edge Node", "6-core / 16 GB / 512 GB", "Backorder", money(99900)),
                listOf("WS-1700", "Aurora Render", "16-core / 128 GB / 4 TB", "Low", money(329900)),
                listOf("WS-1800", "Aurora Thin Client", "2-core / 4 GB / 64 GB", "In stock", money(29900)),
            ),
        )
        spacer(12.dp)
        catalogCategory(
            "Peripherals",
            listOf(
                listOf("PR-2100", "27\" 4K display panel", "IPS / 60 Hz / USB-C", "In stock", money(31900)),
                listOf("PR-2200", "Wireless keyboard", "Low-profile / quiet", "In stock", money(4900)),
                listOf("PR-2300", "Ergonomic mouse", "6-button / rechargeable", "In stock", money(3250)),
                listOf("PR-2400", "USB-C docking station", "Dual 4K / 100 W PD", "Low", money(13900)),
                listOf("PR-2500", "1080p webcam", "Auto-focus / dual mic", "In stock", money(6900)),
                listOf("PR-2600", "Noise-cancelling headset", "USB / 3.5 mm", "In stock", money(8900)),
                listOf("PR-2700", "Desk microphone", "Cardioid / USB", "Backorder", money(7900)),
            ),
        )
        spacer(16.dp)
        photoGrid(photos.takeLast(3) + photos.take(3), perRow = 3, cellHeight = 110.dp, gap = 8.dp, fit = PhotoFit.Cover)
        spacer(16.dp)
        catalogCategory(
            "Networking",
            listOf(
                listOf("NW-3100", "8-port gigabit switch", "Unmanaged / fanless", "In stock", money(4500)),
                listOf("NW-3200", "24-port managed switch", "L2+ / PoE", "Low", money(28900)),
                listOf("NW-3300", "Wi-Fi 6 access point", "Dual-band / PoE", "In stock", money(15900)),
                listOf("NW-3400", "Desktop router", "Wi-Fi 6 / 4 LAN", "In stock", money(11900)),
                listOf("NW-3500", "Patch cable, 2 m (10 pk)", "Cat 6 / shielded", "In stock", money(2900)),
                listOf("NW-3600", "Rack patch panel", "24-port / Cat 6", "Low", money(6900)),
            ),
        )
        spacer(12.dp)
        catalogCategory(
            "Accessories",
            listOf(
                listOf("AC-4100", "Braided USB-C cable (2 m)", "100 W / 10 Gbps", "In stock", money(1190)),
                listOf("AC-4200", "Laptop stand", "Aluminium / adjustable", "In stock", money(4200)),
                listOf("AC-4300", "Surge protector (6-way)", "2 USB-A / 1 USB-C", "In stock", money(2600)),
                listOf("AC-4400", "Cable management kit", "Trays + ties", "In stock", money(1800)),
                listOf("AC-4500", "Monitor arm (single)", "VESA / gas spring", "Low", money(5400)),
                listOf("AC-4600", "Privacy filter, 27\"", "Anti-glare", "In stock", money(3900)),
            ),
        )
    }

    private fun ContainerScope.catalogCategory(title: String, items: List<List<String>>) {
        text(title, h2)
        spacer(6.dp)
        styledTable(
            columns = listOf(
                PdfColumn(1.4f, "SKU"),
                PdfColumn(3.4f, "Product"),
                PdfColumn(2.4f, "Specification"),
                PdfColumn(1.3f, "Stock", TextAlign.Center),
                PdfColumn(1.5f, "Price", TextAlign.End),
            ),
        ) { items.forEach { row(it) } }
    }

    // =======================================================================================
    // 10) Service agreement — numbered sections of long wrapping paragraphs (paginate) + signatures.
    // =======================================================================================
    fun serviceAgreement(): PdfDocumentSpec = pdfDocument(PageConfig(margin = 50.dp, pageNumbers = true)) {
        header {
            text("Master Services Agreement", small.copy(color = muted))
            spacer(4.dp)
            divider(hairline)
        }
        footer { text("Contoso Ltd. & Northwind Traders — Confidential", small.copy(align = TextAlign.Center)) }

        text("Master Services Agreement", h1)
        text("Between Contoso Ltd. (\"Provider\") and Northwind Traders (\"Client\")", small)
        spacer(4.dp)
        text("Effective date: 1 July 2026", small)
        spacer(8.dp)

        val sections = listOf(
            "Definitions", "Scope of Services", "Term and Renewal", "Fees and Payment",
            "Service Levels", "Client Responsibilities", "Confidentiality", "Intellectual Property",
            "Data Protection", "Warranties", "Limitation of Liability", "Indemnification",
            "Termination", "Force Majeure", "Governing Law", "Entire Agreement",
        )
        sections.forEachIndexed { i, t -> section("${i + 1}. $t", lorem(if (i % 3 == 0) 3 else 2, i)) }

        spacer(18.dp)
        signatures()
    }

    private fun ContainerScope.section(title: String, paragraphs: List<String>) {
        spacer(10.dp)
        box {
            text(title, h2)
            spacer(4.dp)
            paragraphs.forEach { p ->
                text(p, body)
                spacer(6.dp)
            }
        }
    }

    private fun ContainerScope.signatures() {
        text("Signatures", h2)
        spacer(24.dp)
        row(gap = 30.dp) {
            cell(1f) {
                divider(ink)
                spacer(3.dp)
                text("For Contoso Ltd.", smallInk)
                text("Name / title / date", small)
            }
            cell(1f) {
                divider(ink)
                spacer(3.dp)
                text("For Northwind Traders", smallInk)
                text("Name / title / date", small)
            }
        }
    }

    private val loremPool = listOf(
        "The parties shall perform their respective obligations with reasonable skill and care, in line with the standards generally accepted in the industry and any written specifications agreed between them.",
        "Each party retains ownership of its pre-existing materials; any deliverable created specifically for this engagement transfers to the Client upon full payment of the applicable fees.",
        "Either party may terminate this agreement on thirty days' written notice, without prejudice to any rights or obligations accrued before the effective date of termination.",
        "Neither party shall be liable for any indirect, incidental, or consequential loss, and each party's aggregate liability shall not exceed the fees paid in the preceding twelve months.",
        "Confidential information disclosed under this agreement shall be used solely for its purposes and protected with the same degree of care a party applies to its own confidential information.",
        "Invoices are payable within thirty days of receipt; amounts not disputed in good faith and left unpaid may accrue interest at the rate permitted by applicable law.",
        "The Client shall provide timely access to the personnel, systems, and information reasonably required for the Provider to deliver the services described in the applicable statement of work.",
        "This agreement constitutes the entire understanding between the parties and supersedes all prior discussions; any amendment must be made in writing and signed by an authorized representative of each party.",
    )

    private fun lorem(n: Int, seed: Int): List<String> = (0 until n).map { k ->
        loremPool[(seed * 3 + k) % loremPool.size] + " " + loremPool[(seed * 5 + k + 1) % loremPool.size]
    }

    // =======================================================================================
    // 11) Résumé / CV — weighted two-column layout, section headings, skills table.
    // =======================================================================================
    fun resume(): PdfDocumentSpec = pdfDocument(PageConfig(margin = 44.dp, pageNumbers = false)) {
        text("Jordan A. Carter", TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ink))
        text("Senior Software Engineer", TextStyle(fontSize = 12.sp, color = accent))
        spacer(6.dp)
        divider(hairline)
        spacer(14.dp)

        row(gap = 26.dp) {
            cell(1f) {
                text("CONTACT", sidebarHead)
                spacer(4.dp)
                text("jordan.carter@example.com", smallInk)
                text("+1 (555) 0100", smallInk)
                text("Bayview, CA", smallInk)
                text("github.com/example", smallInk)
                spacer(14.dp)

                text("SKILLS", sidebarHead)
                spacer(4.dp)
                styledTable(
                    columns = listOf(PdfColumn(2f, "Skill"), PdfColumn(1f, "Level", TextAlign.End)),
                ) {
                    row("Kotlin", "Expert")
                    row("Swift", "Advanced")
                    row("TypeScript", "Advanced")
                    row("PDF / graphics", "Advanced")
                    row("CI / CD", "Proficient")
                }
                spacer(14.dp)

                text("EDUCATION", sidebarHead)
                spacer(4.dp)
                text("B.Sc. Computer Science", smallInk.copy(fontWeight = FontWeight.Bold))
                text("State University · 2014–2018", small)
            }
            cell(2f) {
                text("SUMMARY", h2)
                spacer(5.dp)
                text(
                    "Senior engineer with eight years building cross-platform applications and developer " +
                        "tooling. Comfortable owning a feature end to end — from API design through UI polish " +
                        "and release — with a track record of shipping reliable, well-tested software.",
                    body,
                )
                spacer(12.dp)
                text("EXPERIENCE", h2)
                spacer(6.dp)
                experienceItem(
                    "Senior Software Engineer", "Contoso Ltd.", "2021 – present",
                    listOf(
                        "Led a small team building a multiplatform document-export pipeline.",
                        "Cut export time by 60% by replacing a bitmap renderer with a vector engine.",
                        "Mentored three junior engineers and ran the team's code-review practice.",
                    ),
                )
                experienceItem(
                    "Software Engineer", "Northwind Traders", "2018 – 2021",
                    listOf(
                        "Shipped the company's first mobile client across iOS and Android.",
                        "Introduced automated UI testing, reducing release regressions markedly.",
                    ),
                )
                experienceItem(
                    "Junior Developer", "Fabrikam, Inc.", "2016 – 2018",
                    listOf(
                        "Maintained internal web tools and migrated legacy reports to a new stack.",
                    ),
                )
            }
        }
    }

    private fun ContainerScope.experienceItem(role: String, company: String, period: String, bullets: List<String>) {
        row {
            cell(3f) { text(role, body.copy(fontWeight = FontWeight.Bold)) }
            cell(1f) { text(period, small.copy(align = TextAlign.End)) }
        }
        text(company, smallInk.copy(color = accent))
        spacer(3.dp)
        bullets.forEach { text("•  $it", smallInk) }
        spacer(9.dp)
    }

    // =======================================================================================
    // 12) Event programme — header band + agenda schedule tables.
    // =======================================================================================
    fun eventProgram(): PdfDocumentSpec = pdfDocument(PageConfig(margin = 40.dp, pageNumbers = false)) {
        header {
            row {
                cell(1f) { text("DevConf 2026", TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent)) }
                cell(1f) { text("Conference Programme", small.copy(align = TextAlign.End)) }
            }
            spacer(4.dp)
            divider(hairline)
        }
        footer { text("Riverside Convention Centre · #devconf26", small.copy(align = TextAlign.Center)) }

        text("Conference Programme", h1)
        text("Riverside Convention Centre · 14–15 October 2026", small)
        spacer(14.dp)

        text("Day 1 — Thursday", h2)
        spacer(6.dp)
        agendaTable(
            listOf(
                listOf("08:30", "Registration & coffee", "—", "Foyer"),
                listOf("09:15", "Keynote: The next decade of tooling", "A. Johnson", "Hall A"),
                listOf("10:30", "Designing resilient APIs", "S. Patel", "Room 1"),
                listOf("11:30", "Multiplatform UI in practice", "M. Garcia", "Room 2"),
                listOf("12:30", "Lunch", "—", "Foyer"),
                listOf("13:45", "Rendering documents without a browser", "J. Carter", "Hall A"),
                listOf("15:00", "Performance profiling workshop", "R. Brown", "Room 3"),
                listOf("16:30", "Lightning talks", "Community", "Hall A"),
                listOf("17:30", "Welcome reception", "—", "Terrace"),
            ),
        )
        spacer(16.dp)
        text("Day 2 — Friday", h2)
        spacer(6.dp)
        agendaTable(
            listOf(
                listOf("09:00", "Type systems for the rest of us", "C. Morgan", "Hall A"),
                listOf("10:15", "Shipping accessible software", "D. Nguyen", "Room 1"),
                listOf("11:15", "Scaling CI for big repos", "S. Patel", "Room 2"),
                listOf("12:15", "Lunch", "—", "Foyer"),
                listOf("13:30", "Panel: open source sustainability", "Panel", "Hall A"),
                listOf("15:00", "Closing keynote", "A. Johnson", "Hall A"),
                listOf("16:00", "Farewell", "—", "Foyer"),
            ),
        )
    }

    private fun ContainerScope.agendaTable(rows: List<List<String>>) {
        styledTable(
            columns = listOf(
                PdfColumn(1.2f, "Time"),
                PdfColumn(3.6f, "Session"),
                PdfColumn(1.8f, "Speaker"),
                PdfColumn(1.2f, "Room", TextAlign.Center),
            ),
        ) { rows.forEach { row(it) } }
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
