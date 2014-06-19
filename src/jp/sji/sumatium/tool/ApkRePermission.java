package jp.sji.sumatium.tool;

import com.android.sdklib.build.ApkBuilder;

import pxb.android.axml.AxmlReader;
import pxb.android.axml.AxmlVisitor;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.NodeVisitor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ApkRePermission {

	byte[] buf = new byte[64 * 1024];
	int buf_readLen;

	String libDir = new File(AxmlReader.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getParentFile().getParent();

	public static void main(String[] args) {
		new ApkRePermission(args);
	}

	File inputApkFile;
	File outputApkFile;
	File fDebugKeyStoreFile;
	ArrayList<String> toBeAddedPermList = new ArrayList<String>();
	ArrayList<String> toBeDeletedPermList = new ArrayList<String>();
	boolean changed;

	public ApkRePermission(String[] args) {
		if (args.length > 3) {
			for (int i = 3; i < args.length; i++) {
				if (args[i].startsWith("-")) {
					toBeDeletedPermList.add(args[i].substring(1));
				} else if (args[i].startsWith("+")) {
					toBeAddedPermList.add(args[i].substring(1));
				} else {
					toBeAddedPermList.add(args[i]);
				}
			}
		} else {
			LOG("Need arguments: inputApkFile outputApkFile debugKeyStoreFile permissionToBeAdded permissionToBeAdded ... -permissionToBeDeleted -permissionToBeDeleted ...");
			LOG("Note: debugKeyStoreFile can be \"\" means do not sign result apk file");
			System.exit(1);
		}

		try {
			inputApkFile = new File(args[0]);
			if (!inputApkFile.exists()) {
				LOG("file " + inputApkFile + " does not exist");
				System.exit(1);
			}
			LOG("use inputApkFile: " + inputApkFile);

			outputApkFile = new File(args[1]);
			if (!outputApkFile.exists()) {
				outputApkFile.createNewFile();
			}
			LOG("use outputApkFile: " + outputApkFile);

			String debugKeyStoreFile = args[2];
			if (debugKeyStoreFile.length() > 0) {
				fDebugKeyStoreFile = new File(debugKeyStoreFile);
				if (!fDebugKeyStoreFile.exists()) {
					LOG("file " + fDebugKeyStoreFile + " does not exist");
					System.exit(1);
				}
				LOG("use debugKeyStoreFile: " + fDebugKeyStoreFile);
			} else {
				LOG("debugKeyStoreFile is not specified. The result APK file will not be signed (so can not be installed/published directly).");
			}

			File fResZip = File.createTempFile("res", ".zip");
			try {
				ZipOutputStream zosResZip = new ZipOutputStream(new FileOutputStream(fResZip));
				try {
					modifyManifest(/*save result to:*/zosResZip);
					File fClassesDex = File.createTempFile("classes", ".dex");
					try {
						LOG("extract other files");
						extractFilesTo(fClassesDex, zosResZip);
						zosResZip.close();
						LOG("package all to APK");
						ApkBuilder apkBuilder = new ApkBuilder(outputApkFile, fResZip, fClassesDex, debugKeyStoreFile != null ? fDebugKeyStoreFile.getPath() : null, null);
						apkBuilder.sealApk();
						LOG("OK");
						LOG("");
					} finally {
						fClassesDex.delete();
					}
				} finally {
					zosResZip.close();
				}
			} finally {
				fResZip.delete();
			}

			System.exit(changed ? 0 : 2);

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	private void modifyManifest(ZipOutputStream zos) throws Exception {
		final boolean[] foundToBeAdded = new boolean[toBeAddedPermList.size()];
		final String NS = "http://schemas.android.com/apk/res/android";

		class MyNodeVisitor extends NodeVisitor {
			String nodeName = "";

			MyNodeVisitor(NodeVisitor nv, String nodeName) {
				super(nv);
				this.nodeName = nodeName;
			}

			@Override
			public NodeVisitor child(String ns, String name) {
				if (ns == null && ("manifest".equals(nodeName) || "uses-permission".equals(name))) {
					return new MyNodeVisitor(super.child(ns, name), name);
				}
				return super.child(ns, name);
			}

			@Override
			public void attr(String ns, String name, int resourceId, int type, Object val) {
				if ("uses-permission".equals(nodeName) && "name".equals(name) && NS.equals(ns) && val != null && val instanceof String && type == NodeVisitor.TYPE_STRING) {
					int i = toBeAddedPermList.indexOf(val);
					if (i >= 0) {
						LOG("have found " + val + " -------------");
						foundToBeAdded[i] = true;
					}
					i = toBeDeletedPermList.indexOf(val);
					if (i >= 0) {
						LOG("remove " + val + " xxxxxxxxxxxxxxxxxxx ");
						changed = true;
						return;
					}
				}
				super.attr(ns, name, resourceId, type, val);
			}

			@Override
			public void end() {
				if ("manifest".equals(nodeName)) {
					for (int i = 0; i < foundToBeAdded.length; i++) {
						if (!foundToBeAdded[i]) {
							String perm = toBeAddedPermList.get(i);
							LOG("add " + perm + " ++++++++++++++++");
							NodeVisitor newNode = super.child(null, "uses-permission");
							newNode.attr(NS, "name", pxb.android.axml.R.attr.name, TYPE_STRING, perm);
							changed = true;
						}
					}
				}
				super.end();
			}
		}

		ZipInputStream in = new ZipInputStream(new FileInputStream(inputApkFile));
		try {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				if (!entry.isDirectory() && entry.getName().equals("AndroidManifest.xml")) {
					break;
				}
			}

			if (entry != null) {
				zos.putNextEntry(new ZipEntry(entry.getName()));

				byte[] oldData;
				{
					ByteArrayOutputStream bos = new ByteArrayOutputStream(buf.length);
					while ((buf_readLen = in.read(buf)) > 0) {
						bos.write(buf, 0, buf_readLen);
					}
					oldData = bos.toByteArray();
				}
				in.read(oldData);
				AxmlReader ar = new AxmlReader(oldData);
				AxmlWriter aw = new AxmlWriter();
				ar.accept(new AxmlVisitor(aw) {

					@Override
					public NodeVisitor child(String ns, String name) {
						return new MyNodeVisitor(super.child(ns, name), name);
					}
				});

				zos.write(changed ? aw.toByteArray() : oldData);
			}
		} finally {
			in.close();
		}

	}

	void extractFilesTo(File fClassesDex, ZipOutputStream others) throws Exception {
		ZipInputStream in = new ZipInputStream(new FileInputStream(inputApkFile));
		try {
			FileOutputStream outClassesdex = new FileOutputStream(fClassesDex);
			try {
				ZipEntry entry;
				while ((entry = in.getNextEntry()) != null) {
					if (entry.isDirectory() || entry.getName().equals("AndroidManifest.xml")) {
						continue;
					}
					boolean matched = entry.getName().equals("classes.dex");
					if (!matched) {
						ZipEntry newEntry = entry.getMethod() == ZipEntry.DEFLATED ? new ZipEntry(entry.getName()) : new ZipEntry(entry);
						others.putNextEntry(newEntry);
					}
					while ((buf_readLen = in.read(buf)) > 0) {
						(matched ? outClassesdex : others).write(buf, 0, buf_readLen);
					}
				}
			} finally {
				outClassesdex.close();
			}
		} finally {
			in.close();
		}
	}

	interface ZipEntryFilter {
		boolean accept(ZipEntry entry);
	}

	void LOG(String s) {
		System.err.println(s);
	}
}