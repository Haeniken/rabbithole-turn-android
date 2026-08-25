/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.configStore

import android.content.Context
import android.util.Log
import com.wireguard.android.R
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import com.wireguard.android.util.SecureFileStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Configuration store that uses a `wg-quick`-style file for each configured tunnel.
 */
class FileConfigStore(private val context: Context) : ConfigStore {
    private val secureStore = SecureFileStore(context)

    fun migrateLegacyFiles() {
        context.fileList()
            .filter { it.endsWith(CONFIG_SUFFIX) }
            .forEach { fileName ->
                val file = File(context.filesDir, fileName)
                try {
                    val stored = secureStore.read(file)
                    secureStore.migrateIfNeeded(file, stored)
                } catch (e: IOException) {
                    Log.e(TAG, "Unable to migrate protected configuration $fileName", e)
                }
            }
    }

    @Throws(IOException::class)
    override fun create(name: String, config: Config): Config {
        Log.d(TAG, "Creating configuration for tunnel $name")
        val file = fileFor(name)
        if (!file.createNewFile())
            throw IOException(context.getString(R.string.config_file_exists_error, file.name))
        try {
            secureStore.write(file, config.toWgQuickString().toByteArray(StandardCharsets.UTF_8))
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        return config
    }

    @Throws(IOException::class)
    override fun delete(name: String) {
        Log.d(TAG, "Deleting configuration for tunnel $name")
        val file = fileFor(name)
        if (!file.delete())
            throw IOException(context.getString(R.string.config_delete_error, file.name))
    }

    override fun enumerate(): Set<String> {
        return context.fileList()
            .filter { it.endsWith(CONFIG_SUFFIX) }
            .map { it.removeSuffix(CONFIG_SUFFIX) }
            .toSet()
    }

    private fun fileFor(name: String): File {
        return File(context.filesDir, "$name$CONFIG_SUFFIX")
    }

    @Throws(BadConfigException::class, IOException::class)
    override fun load(name: String): Config {
        val file = fileFor(name)
        val stored = secureStore.read(file)
        val config = ByteArrayInputStream(stored.bytes).use(Config::parse)
        secureStore.migrateIfNeeded(file, stored)
        return config
    }

    @Throws(IOException::class)
    override fun rename(name: String, replacement: String) {
        Log.d(TAG, "Renaming configuration for tunnel $name to $replacement")
        val file = fileFor(name)
        val replacementFile = fileFor(replacement)
        if (!replacementFile.createNewFile()) throw IOException(context.getString(R.string.config_exists_error, replacement))
        try {
            val stored = secureStore.read(file)
            secureStore.write(replacementFile, stored.bytes)
            if (!file.delete()) throw IOException(context.getString(R.string.config_rename_error, file.name))
        } catch (e: IOException) {
            if (!replacementFile.delete()) Log.w(TAG, "Couldn't delete marker file for new name $replacement")
            throw e
        }
    }

    @Throws(IOException::class)
    override fun save(name: String, config: Config): Config {
        Log.d(TAG, "Saving configuration for tunnel $name")
        val file = fileFor(name)
        if (!file.isFile)
            throw FileNotFoundException(context.getString(R.string.config_not_found_error, file.name))
        secureStore.write(file, config.toWgQuickString().toByteArray(StandardCharsets.UTF_8))
        return config
    }

    companion object {
        private const val TAG = "WireGuard/FileConfigStore"
        private const val CONFIG_SUFFIX = ".conf"
    }
}
