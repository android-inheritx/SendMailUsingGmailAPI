package com.example.sendmaildemo

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.work.*
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.util.Utils
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.Base64
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import com.google.api.services.gmail.model.Message
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.doAsyncResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.Exception
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
import kotlin.collections.ArrayList


class MainActivity : AppCompatActivity() {
    var mCredential: GoogleAccountCredential? = null
    var mProgress: ProgressDialog? = null

    private val PREF_ACCOUNT_NAME = "accountName"
    private val ASK_MULTIPLE_PERMISSION_REQUEST_CODE = 202

    private val SCOPES = mutableListOf(
        GmailScopes.GMAIL_SEND
//        GmailScopes.GMAIL_LABELS,
//        GmailScopes.GMAIL_COMPOSE,
//        GmailScopes.GMAIL_INSERT,
//        GmailScopes.GMAIL_MODIFY,
//        GmailScopes.GMAIL_READONLY,
//        GmailScopes.MAIL_GOOGLE_COM
    )

    private val SELECT_VIDEO = 1
    private val REQUEST_ACCOUNT_PICKER = 2
    private val REQUEST_AUTHORIZATION = 3


    var fileName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (AppUtils.checkPermission(this)) {
            init()
            setListener()
        } else {
            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.GET_ACCOUNTS
                ), ASK_MULTIPLE_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun init() {
        mCredential = GoogleAccountCredential.usingOAuth2(
            applicationContext, SCOPES
        ).setBackOff(ExponentialBackOff())

        mProgress = ProgressDialog(this)
        mProgress?.setMessage("Sending...")
        mProgress?.setCancelable(false)
    }

    private fun setListener() {
        btnSelectFile.setOnClickListener {
            pickVideo()
        }

        btnSendEmail.setOnClickListener {
            if (!isGooglePlayServicesAvailable()) {
                acquireGooglePlayServices()
            } else {
                startActivityForResult(
                    mCredential!!.newChooseAccountIntent(),
                    REQUEST_ACCOUNT_PICKER
                )
            }
        }
    }

    private fun pickVideo() {
        val photoPickerIntent = Intent(Intent.ACTION_PICK)
        photoPickerIntent.type = "video/*"
        startActivityForResult(photoPickerIntent, SELECT_VIDEO)
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                init()
                setListener()
            } else {
                Toast.makeText(this, "Please allow permission to continue!", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun sendEmailByWorkManager() {
        //set constraint of internet connected
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        //set worker input data
        val emailData = Data.Builder()
            .putString(AppConstants.FILENAME, fileName)
            .putString(AppConstants.SELECTED_ACCOUNT_NAME, mCredential!!.selectedAccountName)
            .putString(AppConstants.EMAIL_TO, etEmail.text.trim().toString())
            .build()

        val sendEmailRequest = OneTimeWorkRequest
            .Builder(SendMailWorker::class.java)
            .setConstraints(constraints)
            .setInputData(emailData)
            .build()

        WorkManager.getInstance().enqueue(sendEmailRequest)

        WorkManager.getInstance().getWorkInfoByIdLiveData(sendEmailRequest.id).observe(
            this,
            androidx.lifecycle.Observer {
                if (it?.state != null && it.state.isFinished) {
                    val outputData = it.outputData

                    val responseCode = outputData.getInt(AppConstants.MAIL_RESPONSE_CODE, 0)

                    when (responseCode) {
                        AppConstants.SUCCESS_RESPONSE__MAIL_SENT -> {
                            Toast.makeText(this, "Mail sent successfully", Toast.LENGTH_SHORT)
                                .show()
                        }

                        AppConstants.ERROR_RESPONSE_MAIL_DOMAIN_INVALID -> {
                            Toast.makeText(
                                this, "You can send mail using @gmail.com only", Toast.LENGTH_SHORT
                            ).show()
                        }

                        AppConstants.ERROR_RESPONSE_AUTHORIZATION_FAILED -> {
                            Toast.makeText(
                                this, "Authorization failed", Toast.LENGTH_SHORT
                            ).show()

//                            startActivityForResult(
//                                mCredential!!.newChooseAccountIntent(),
//                                REQUEST_ACCOUNT_PICKER
//                            )
//                            startActivityForResult(e.intent, REQUEST_AUTHORIZATION)

//                            sendEmail()
                        }
                    }
                }
            }
        )
    }

    private fun sendEmail() {
        var mService: Gmail? = null
        var mLastError = null

        val transport = AndroidHttp.newCompatibleTransport()
        val jsonFactory = JacksonFactory.getDefaultInstance()

        mService = Gmail.Builder(
            transport, jsonFactory, mCredential
        ).setApplicationName(resources.getString(R.string.app_name))
            .build()


        // getting Values for to Address, from Address, Subject and Body
        val user = "me"
        val to = etEmail.text.trim().toString()
        val from = mCredential!!.selectedAccountName
        val subject = "DemoSubject"
        val body = "Demo body"
        var mimeMessage: MimeMessage? = null
        var response = ""

        val toArrayList = to.split(",")

        mProgress!!.show()
        doAsync {
            try {
                mimeMessage = createEmail(toArrayList, from, subject, body)

                var message = createMessageWithEmail(mimeMessage!!)

//                GMail's official method to send email with oauth2.0
                message = mService.users().messages().send(user, message).execute()

                runOnUiThread {
                    mProgress!!.hide()
                    if (message.id != null && message.id != "") {
                        Toast.makeText(
                            this@MainActivity,
                            "Mail sent successfully",
                            Toast.LENGTH_SHORT
                        )
                            .show()
                    }
                }

                println("Message id: " + message.id)
                println(message.toPrettyString())

            } catch (e: UserRecoverableAuthIOException) {
                runOnUiThread {
                    mProgress!!.hide()
                }
                startActivityForResult(e.intent, REQUEST_AUTHORIZATION)
            } catch (e: GoogleJsonResponseException) {
                runOnUiThread {
                    mProgress!!.hide()
                    if (e.details.errors[0].domain.equals("global", true)) {
                        Toast.makeText(
                            this@MainActivity,
                            "Mail not send, Please use Gmail id",
                            Toast.LENGTH_SHORT
                        )
                            .show()
                    } else {
                        Toast.makeText(this@MainActivity, "Can't send mail", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    mProgress!!.hide()
                }
                e.printStackTrace()
            }
        }
    }

    // Method for Checking Google Play Service is Available
    fun isGooglePlayServicesAvailable(): Boolean {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(this)
        return connectionStatusCode == ConnectionResult.SUCCESS
    }


    // Method to Show Info, If Google Play Service is Not Available.
    private fun acquireGooglePlayServices() {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(this)
        if (apiAvailability.isUserResolvableError(connectionStatusCode)) {
            showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode)
        }
    }

    // Method for Google Play Services Error Info
    private fun showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode: Int) {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val dialog = apiAvailability.getErrorDialog(
            this,
            connectionStatusCode,
            101
        )
        dialog.show()
    }

    // Method to create email Params
    @Throws(MessagingException::class)
    private fun createEmail(
        to: List<String>,
        from: String,
        subject: String,
        bodyText: String
    ): MimeMessage? {
        val props = Properties()
        val session: Session = Session.getDefaultInstance(props, null)
        val email = MimeMessage(session)
        val fAddress = InternetAddress(from)
        email.setFrom(fAddress)

        to.forEachIndexed { index, s ->
            val tAddress = InternetAddress(s)
            email.addRecipient(javax.mail.Message.RecipientType.TO, tAddress)
        }


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


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)


        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                SELECT_VIDEO -> {
                    val videoUri = data!!.data
                    fileName = getPathFromURI(videoUri!!).orEmpty()

                    // Get file from file name
                    val file = File(fileName)
                    val fileSizeInBytes = file.length()
                    val fileSizeInKB = fileSizeInBytes / 1024 //in KB
                    val fileSizeInMB = fileSizeInKB / 1024 // in MB

                    if (fileSizeInMB > 35) {
                        Toast.makeText(
                            this,
                            "Please select file with size less than 35MB.",
                            Toast.LENGTH_SHORT
                        ).show()
                        fileName = ""
                        pickVideo()
                    } else {
                        tvSelectedFileName.text = fileName
                    }
                }

                REQUEST_ACCOUNT_PICKER -> {
                    val accountName: String =
                        data!!.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)!!
                    mCredential!!.selectedAccountName = accountName
                    sendEmail()
                }

                REQUEST_AUTHORIZATION -> {
                    sendEmail()
                }
            }
        }
    }

    private fun getPathFromURI(contentUri: Uri): String? {
        var res: String? = null
        val proj = arrayOf(MediaStore.Video.Media.DATA)
        val cursor: Cursor = contentResolver.query(contentUri, proj, "", null, "")!!
        if (cursor.moveToFirst()) {
            val column_index: Int = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            res = cursor.getString(column_index)
        }
        cursor.close()
        return res
    }

}


