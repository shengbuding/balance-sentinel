package com.balancesentinel.app.ui

import android.content.Context
import com.balancesentinel.app.R
import com.balancesentinel.app.data.debug.DebugReportLabels

internal fun Context.debugReportLabels(): DebugReportLabels = DebugReportLabels(
    status = getString(R.string.debug_report_status),
    timestamp = getString(R.string.debug_report_timestamp),
    duration = getString(R.string.debug_report_duration),
    account = getString(R.string.debug_report_account),
    provider = getString(R.string.debug_report_provider),
    baseUrl = getString(R.string.debug_report_base_url),
    endpoint = getString(R.string.debug_report_endpoint),
    customScript = getString(R.string.debug_report_custom_script),
    yes = getString(R.string.debug_report_yes),
    requestHeaders = getString(R.string.debug_report_request_headers),
    requestBody = getString(R.string.debug_report_request_body),
    responseHeaders = getString(R.string.debug_report_response_headers),
    responseBody = getString(R.string.debug_report_response_body),
    error = getString(R.string.debug_report_error),
    exceptionType = getString(R.string.debug_report_exception_type),
    stack = getString(R.string.debug_report_stack),
    scriptCharacters = getString(R.string.debug_report_script_characters),
    scriptBytes = getString(R.string.debug_report_script_bytes),
    scriptSha256 = getString(R.string.debug_report_script_sha256)
)
