package com.example.sendmaildemo

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.Base64
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import com.google.api.services.gmail.model.Message
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.BodyPart
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

class SendMailWorker(context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {
    override fun doWork(): Result {
        val filename = inputData.getString(AppConstants.FILENAME)
        val selectedAccountName = inputData.getString(AppConstants.SELECTED_ACCOUNT_NAME)
        val emailTo = inputData.getString(AppConstants.EMAIL_TO)

        return sendEmail(filename!!, selectedAccountName!!, emailTo!!)
    }

    private fun sendEmail(fileName: String, selectedAccountName: String, emailTo: String): Result {

        val SCOPES = mutableListOf(
            GmailScopes.GMAIL_SEND
//        GmailScopes.GMAIL_LABELS,
//        GmailScopes.GMAIL_COMPOSE,
//        GmailScopes.GMAIL_INSERT,
//        GmailScopes.GMAIL_MODIFY,
//        GmailScopes.GMAIL_READONLY,
//        GmailScopes.MAIL_GOOGLE_COM
        )

        val mCredential = GoogleAccountCredential.usingOAuth2(
            applicationContext, SCOPES
        ).setBackOff(ExponentialBackOff())

        mCredential.selectedAccountName = selectedAccountName

        val transport = AndroidHttp.newCompatibleTransport()
        val jsonFactory = JacksonFactory.getDefaultInstance()

        val mService = Gmail.Builder(
            transport, jsonFactory, mCredential
        ).setApplicationName(applicationContext.resources.getString(R.string.app_name))
            .build()


        // getting Values for to Address, from Address, Subject and Body
        val user = "me"
        val to = emailTo
        val from = mCredential!!.selectedAccountName
        val subject = "DemoSubject"
        val body = "Demo body"
        var mimeMessage: MimeMessage? = null
        var response = ""

        try {
            mimeMessage = createEmail(to, from, subject, body, fileName)

            var message = createMessageWithEmail(mimeMessage!!)

//                GMail's official method to send email with oauth2.0
            message = mService.users().messages().send(user, message).execute()


            println("Message id: " + message.id)
            println(message.toPrettyString())

            if (message.id == null || message.id == "") {
                val workerResponseData = androidx.work.Data.Builder()
                    .putInt(
                        AppConstants.MAIL_RESPONSE_CODE,
                        AppConstants.ERROR_RESPONSE_MAIL_DOMAIN_INVALID
                    )
                    .build()

                return Result.failure(workerResponseData)
            } else {
                val workerResponseData = androidx.work.Data.Builder()
                    .putInt(
                        AppConstants.MAIL_RESPONSE_CODE,
                        AppConstants.SUCCESS_RESPONSE__MAIL_SENT
                    )
                    .build()

                return Result.success(workerResponseData)
            }
        } catch (e: UserRecoverableAuthIOException) {
            val workerResponseData = androidx.work.Data.Builder()
                .putInt(
                    AppConstants.MAIL_RESPONSE_CODE,
                    AppConstants.ERROR_RESPONSE_AUTHORIZATION_FAILED
                ).build()

            return Result.failure(workerResponseData)
//            startActivityForResult(e.intent, REQUEST_AUTHORIZATION)
//            e.printStackTrace()
        }
    }


    // Method to create email Params
    @Throws(MessagingException::class)
    private fun createEmail(
        to: String,
        from: String,
        subject: String,
        bodyText: String,
        fileName: String
    ): MimeMessage? {
        val props = Properties()
        val session: Session = Session.getDefaultInstance(props, null)
        val email = MimeMessage(session)
        val tAddress = InternetAddress(to)
        val fAddress = InternetAddress(from)
        email.setFrom(fAddress)
        email.addRecipient(javax.mail.Message.RecipientType.TO, tAddress)
        email.subject = subject
        // Create Multipart object and add MimeBodyPart objects to this object
        val multipart: Multipart = MimeMultipart()
        // Changed for adding attachment and text
        email.setText(bodyText)
        val textBody: BodyPart = MimeBodyPart()
        textBody.setText(bodyText)

        multipart.addBodyPart(textBody)
        if (fileName != "") { // Create new MimeBodyPart object and set DataHandler object to this object
            val attachmentBody = MimeBodyPart()
            val filename: String = fileName // change accordingly
            val source = FileDataSource(filename)
            attachmentBody.dataHandler = DataHandler(source)
            attachmentBody.fileName = filename
            multipart.addBodyPart(attachmentBody)
        }

        //Set the multipart object to the message object
        email.setContent(multipart)
        return email
    }

    @Throws(MessagingException::class, IOException::class)
    private fun createMessageWithEmail(email: MimeMessage): Message? {
        val bytes = ByteArrayOutputStream()
        email.writeTo(bytes)
        val encodedEmail: String = Base64.encodeBase64URLSafeString(bytes.toByteArray())
        val message = Message()
        message.raw = encodedEmail
        return message
    }

}