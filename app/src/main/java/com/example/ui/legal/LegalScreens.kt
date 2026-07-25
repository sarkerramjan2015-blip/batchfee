package com.batchfee.edu.ui.legal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LegalBackground = Color(0xFF020617)
private val LegalCard = Color(0xFF0F172A)
private val LegalBorder = Color(0xFF1E293B)
private val LegalText = Color(0xFFE2E8F0)
private val LegalMuted = Color(0xFF94A3B8)
private val LegalAccent = Color(0xFF22D3EE)

private data class LegalSection(
    val title: String,
    val body: String
)

@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    LegalDocumentScreen(
        title = "Privacy Policy",
        subtitle = "Last updated: June 2026",
        intro = "BatchFee is an education management app for coaching centers, tutors, academies, and institutes. This policy explains what information the app handles and how it is used to manage students, batches, fees, attendance, staff, and institute subscriptions.",
        sections = privacyPolicySections,
        onBack = onBack
    )
}

@Composable
fun TermsConditionsScreen(onBack: () -> Unit) {
    LegalDocumentScreen(
        title = "Terms & Conditions",
        subtitle = "Last updated: June 2026",
        intro = "These terms apply when you install, register for, or use BatchFee. By using the app, you agree to use it responsibly for institute management and to protect the data entered into your account.",
        sections = termsSections,
        onBack = onBack
    )
}

@Composable
private fun LegalDocumentScreen(
    title: String,
    subtitle: String,
    intro: String,
    sections: List<LegalSection>,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(LegalBackground, Color(0xFF08111F), LegalBackground)
                )
            )
            .padding(horizontal = 18.dp, vertical = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = LegalText)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = LegalText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
                Text(text = subtitle, color = LegalMuted, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                LegalCardBlock {
                    Text(
                        text = intro,
                        color = LegalText,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 21.sp
                    )
                }
            }

            items(sections) { section ->
                LegalCardBlock {
                    Text(
                        text = section.title,
                        color = LegalAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = section.body,
                        color = LegalText,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 21.sp
                    )
                }
            }

            item {
                Text(
                    text = "For questions, contact BatchFee support on WhatsApp: +8801518657869",
                    color = LegalMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun LegalCardBlock(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = LegalCard.copy(alpha = 0.92f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, LegalBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

private val privacyPolicySections = listOf(
    LegalSection(
        "1. Information BatchFee Stores",
        "BatchFee may store institute details, owner and staff account details, student profiles, phone numbers, class and batch information, attendance records, fee records, receipts, expenses, salary records, enquiry records, exam results, reminder templates, profile photos, and app settings."
    ),
    LegalSection(
        "2. How Information Is Used",
        "The app uses this information to run institute operations: login, student management, batch enrollment, fee collection, receipt generation, due reminders, attendance tracking, reports, staff management, billing, subscription checks, backup, export, and support."
    ),
    LegalSection(
        "3. Firebase and Cloud Services",
        "BatchFee uses Google Firebase services such as Firebase Authentication, Cloud Firestore, Crashlytics, Analytics, and Play Integrity App Check where enabled. These services may process account, device, crash, usage, and cloud sync data according to Google's Firebase privacy and security terms."
    ),
    LegalSection(
        "4. Local Device Data",
        "Some institute data is stored locally on the device using the app database and preferences so the app can load faster and support institute workflows. Users should protect their device with a screen lock and avoid sharing admin accounts."
    ),
    LegalSection(
        "5. Photos, Camera, and Files",
        "When you add student or profile photos, BatchFee may use camera, gallery, and file sharing features. Exported CSV files, receipts, and reports may contain student, staff, attendance, and financial information, so they should only be shared with trusted recipients."
    ),
    LegalSection(
        "6. Data Sharing",
        "BatchFee does not sell institute or student data. Data may be shared only when you choose to export, print, send messages through apps like WhatsApp or SMS, contact support, or when required for cloud services, legal compliance, fraud prevention, security, and app reliability."
    ),
    LegalSection(
        "7. Children's Data",
        "Institutes may enter data about students, including minors. The institute is responsible for having proper permission from parents or guardians where required and for using student data only for education management purposes."
    ),
    LegalSection(
        "8. Data Security",
        "BatchFee aims to protect data using account login, app permissions, local storage controls, Firebase security features, and platform safeguards. No system is perfectly secure, so administrators should use strong passwords, limit staff access, and keep devices updated."
    ),
    LegalSection(
        "9. Data Retention and Deletion",
        "Institute data is retained while the account is active or as needed for records, billing, support, legal, and operational reasons. To request account or data deletion, contact BatchFee support with your registered institute details."
    ),
    LegalSection(
        "10. Policy Updates",
        "This policy may be updated as BatchFee features, services, or legal requirements change. Continued use of the app after an update means you accept the updated policy."
    )
)

private val termsSections = listOf(
    LegalSection(
        "1. Account Responsibility",
        "You are responsible for the accuracy of institute, student, staff, fee, attendance, and billing information entered into BatchFee. Keep your login credentials private and make sure staff accounts are assigned only the permissions they need."
    ),
    LegalSection(
        "2. Acceptable Use",
        "You agree to use BatchFee only for lawful education and institute management purposes. You must not misuse the app, attempt unauthorized access, upload harmful content, spam registration forms, or use the app to violate privacy or data protection laws."
    ),
    LegalSection(
        "3. Student and Guardian Data",
        "If you enter student or guardian information, you confirm that you have the authority or consent needed to collect and manage that information for institute operations, attendance, fee collection, communication, reports, and academic records."
    ),
    LegalSection(
        "4. Fees, Receipts, and Reports",
        "BatchFee helps record payments, dues, receipts, expenses, salary, and reports. You are responsible for reviewing financial records, correcting mistakes, keeping backups, and complying with accounting, tax, and local legal requirements."
    ),
    LegalSection(
        "5. Messaging and Exports",
        "The app may help launch WhatsApp, SMS, print, share, or export actions. You are responsible for checking the message, recipient, file, and content before sending or sharing any student, staff, or financial information."
    ),
    LegalSection(
        "6. Subscription and Access",
        "Some features may depend on trial, subscription, institute status, student limits, staff limits, or billing status. BatchFee may limit, suspend, or block access if a subscription expires, payment is not completed, abuse is detected, or these terms are violated."
    ),
    LegalSection(
        "7. Third-Party Services",
        "BatchFee may rely on services from Google Firebase, Android, WhatsApp, SMS apps, device storage, and other platform tools. Their availability, privacy practices, and terms are controlled by those providers."
    ),
    LegalSection(
        "8. Backups and Data Loss",
        "You are responsible for keeping important institute records backed up and verifying exported reports. BatchFee is provided as a management tool and cannot guarantee that every device, network, cloud service, or third-party app will always work without interruption."
    ),
    LegalSection(
        "9. Limitation of Liability",
        "To the maximum extent allowed by law, BatchFee is not liable for indirect losses, business interruption, incorrect data entry, lost records, missed payments, unauthorized account sharing, device failure, or third-party service issues."
    ),
    LegalSection(
        "10. Changes to Terms",
        "BatchFee may update these terms as the app changes. Continued use of the app after changes means you accept the updated terms."
    )
)

