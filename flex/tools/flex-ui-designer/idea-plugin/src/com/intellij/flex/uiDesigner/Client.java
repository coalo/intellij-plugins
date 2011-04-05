package com.intellij.flex.uiDesigner;

import com.intellij.flex.uiDesigner.io.Amf3Types;
import com.intellij.flex.uiDesigner.io.AmfOutputStream;
import com.intellij.flex.uiDesigner.io.BlockDataOutputStream;
import com.intellij.flex.uiDesigner.io.StringRegistry;
import com.intellij.flex.uiDesigner.mxml.MxmlWriter;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.lang.javascript.flex.XmlBackedJSClassImpl;
import com.intellij.lang.javascript.psi.ecmal4.JSClass;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.ArrayUtil;
import gnu.trove.TIntObjectHashMap;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;

public class Client implements Closable {
  protected AmfOutputStream out;
  protected BlockDataOutputStream blockOut;

  private int registeredLibraryCounter;
  protected int sessionId;

  private final MxmlWriter mxmlWriter = new MxmlWriter();

  private final TIntObjectHashMap<ModuleInfo> registeredModules = new TIntObjectHashMap<ModuleInfo>();

  public Client(OutputStream out) {
    setOutput(out);
  }

  public AmfOutputStream getOutput() {
    return out;
  }

  public void setOutput(OutputStream out) {
    blockOut = new BlockDataOutputStream(out);
    this.out = new AmfOutputStream(blockOut);

    mxmlWriter.setOutput(this.out);
  }

  public boolean isModuleRegistered(Module module) {
    return registeredModules.containsKey(module.hashCode());
  }

  @NotNull
  public Module getModule(int id) {
    return registeredModules.get(id).getModule();
  }

  public void flush() throws IOException {
    out.flush();
  }

  @Override
  public void close() throws IOException {
    if (out == null) {
      return;
    }
    blockOut = null;
    registeredLibraryCounter = 0;
    sessionId++;

    registeredModules.clear();
    
    mxmlWriter.reset();

    BinaryFileManager.getInstance().reset(sessionId);

    try {
      out.closeWithoutFlush();
    }
    finally {
      out = null;
    }
  }

  public void openProject(Project project) throws IOException {
    beginMessage(ClientMethod.openProject);
    out.writeInt(project.hashCode());
    out.writeAmfUtf(project.getName());

    Rectangle projectWindowBounds = getProjectWindowBounds(project);
    if (projectWindowBounds == null) {
      out.write(false);
    }
    else {
      out.write(true);
      out.writeShort(projectWindowBounds.x);
      out.writeShort(projectWindowBounds.y);
      out.writeShort(projectWindowBounds.width);
      out.writeShort(projectWindowBounds.height);
    }

    blockOut.end();
  }

  private static Rectangle getProjectWindowBounds(Project project) {
    PropertiesComponent d = PropertiesComponent.getInstance(project);
    try {
      return d.isValueSet("fud_pw_x")
             ? new Rectangle(parsePwV(d, "fud_pw_x"), parsePwV(d, "fud_pw_y"), parsePwV(d, "fud_pw_w"), parsePwV(d, "fud_pw_h"))
             : null;
    }
    catch (NumberFormatException e) {
      return null;
    }
  }

  private static int parsePwV(PropertiesComponent propertiesComponent, String key) {
    int v = Integer.parseInt(propertiesComponent.getValue(key));
    if (v < 0 || v > 65535) {
      throw new NumberFormatException("Value " + v + " out of range 0-65535");
    }
    return v;
  }

  private void beginMessage(ClientMethod method) {
    blockOut.assertStart();
    out.write(ClientMethod.METHOD_CLASS);
    out.write(method);
  }

  public void closeProject(Project project) throws IOException {
    beginMessage(ClientMethod.closeProject);
    out.writeInt(project.hashCode());
    out.flush();
  }

  public void registerLibrarySet(LibrarySet librarySet, StringRegistry.StringWriter stringWriter) throws IOException {
    beginMessage(ClientMethod.registerLibrarySet);
    out.writeAmfUtf(librarySet.getId());
    out.writeInt(-1); // todo parent

    stringWriter.writeTo(out);

    out.write(librarySet.getApplicationDomainCreationPolicy());
    out.writeShort(librarySet.getLibraries().size());
    for (Library library : librarySet.getLibraries()) {
      final OriginalLibrary originalLibrary;
      final boolean unregisteredLibrary;
      if (library instanceof OriginalLibrary) {
        originalLibrary = (OriginalLibrary)library;
        unregisteredLibrary = originalLibrary.sessionId != sessionId;
        out.write(unregisteredLibrary ? 0 : 1);
      }
      else if (library instanceof FilteredLibrary) {
        FilteredLibrary filteredLibrary = (FilteredLibrary)library;
        originalLibrary = filteredLibrary.getOrigin();
        unregisteredLibrary = originalLibrary.sessionId != sessionId;
        out.write(unregisteredLibrary ? 2 : 3);
      }
      else {
        out.write(4);
        out.writeNotEmptyString(((EmbedLibrary)library).getPath());
        continue;
      }

      if (unregisteredLibrary) {
        originalLibrary.id = registeredLibraryCounter++;
        originalLibrary.sessionId = sessionId;
        out.writeAmfUtf(originalLibrary.getPath());
        writeVirtualFile(originalLibrary.getFile(), out);

        if (originalLibrary.inheritingStyles == null) {
          out.writeShort(0);
        }
        else {
          out.write(originalLibrary.inheritingStyles);
        }

        if (originalLibrary.defaultsStyle == null) {
          out.write(0);
        }
        else {
          out.write(1);
          out.write(originalLibrary.defaultsStyle);
        }
      }
      else {
        out.writeShort(originalLibrary.id);
      }
    }

    blockOut.end();
  }

  public void registerModule(Project project, ModuleInfo moduleInfo, String[] librarySetIds, StringRegistry.StringWriter stringWriter)
    throws IOException {
    final int id = moduleInfo.getModule().hashCode();
    registeredModules.put(id, moduleInfo);

    beginMessage(ClientMethod.registerModule);

    stringWriter.writeToIfStarted(out);

    out.writeInt(id);
    out.writeInt(project.hashCode());
    out.write(librarySetIds);
    out.write(moduleInfo.getLocalStyleHolders(), "lsh", true);

    out.reset();

    blockOut.end();
  }

  public void openDocument(Module module, XmlFile psiFile) throws IOException {
    DocumentFactoryManager documentFileManager = DocumentFactoryManager.getInstance(module.getProject());
    VirtualFile virtualFile = psiFile.getVirtualFile();
    FileDocumentManager fileDocumentManager = FileDocumentManager.getInstance();
    if (documentFileManager.isRegistered(virtualFile) && ArrayUtil.indexOf(fileDocumentManager.getUnsavedDocuments(),
                                                                           fileDocumentManager.getDocument(virtualFile)) != -1) {
      updateDocumentFactory(documentFileManager.getId(virtualFile), module, psiFile);
      return;
    }

    int factoryId = registerDocumentFactoryIfNeed(module, psiFile, documentFileManager, false);
    beginMessage(ClientMethod.openDocument);
    out.writeInt(module.hashCode());
    out.writeShort(factoryId);
  }
  
  public void updateDocumentFactory(int factoryId, Module module, XmlFile psiFile) throws IOException {
    beginMessage(ClientMethod.updateDocumentFactory);
    final int moduleId = module.hashCode();
    out.writeInt(moduleId);
    out.writeShort(factoryId);
    writeDocumentFactory(module, psiFile, psiFile.getVirtualFile(), DocumentFactoryManager.getInstance(module.getProject()));

    beginMessage(ClientMethod.updateDocuments);
    out.writeInt(moduleId);
    out.writeShort(factoryId);
  }

  private int registerDocumentFactoryIfNeed(Module module, XmlFile psiFile, DocumentFactoryManager documentFileManager, boolean force)
    throws IOException {
    VirtualFile virtualFile = psiFile.getVirtualFile();
    assert virtualFile != null;
    final boolean registered = !force && documentFileManager.isRegistered(virtualFile);
    final int id = documentFileManager.getId(virtualFile);
    if (!registered) {
      beginMessage(ClientMethod.registerDocumentFactory);
      out.writeInt(module.hashCode());
      writeVirtualFile(virtualFile, out);
      
      JSClass jsClass = XmlBackedJSClassImpl.getXmlBackedClass(psiFile);
      assert jsClass != null;
      out.writeAmfUtf(jsClass.getQualifiedName());
      out.writeShort(id);

      writeDocumentFactory(module, psiFile, virtualFile, documentFileManager);
    }

    return id;
  }
  
  private void writeDocumentFactory(Module module, XmlFile psiFile, VirtualFile virtualFile, DocumentFactoryManager documentFileManager)
    throws IOException {
    XmlFile[] subDocuments = mxmlWriter.write(psiFile);
    if (subDocuments != null) {
      for (XmlFile subDocument : subDocuments) {
        Module moduleForFile = ModuleUtil.findModuleForFile(virtualFile, psiFile.getProject());
        if (module != moduleForFile) {
          FlexUIDesignerApplicationManager.LOG.error("Currently, support subdocument only from current module");
        }

        // force register, subDocuments from unregisteredDocumentFactories, so, it is registered (id allocated) only on server side
        registerDocumentFactoryIfNeed(module, subDocument, documentFileManager, true);
      }
    }
  }

  public void qualifyExternalInlineStyleSource() {
    beginMessage(ClientMethod.qualifyExternalInlineStyleSource);
  }

  @SuppressWarnings({"UnusedDeclaration"})
  public static void writeAmfVirtualFile(@NotNull VirtualFile file, @NotNull AmfOutputStream out) {
    out.write(Amf3Types.OBJECT);
    out.writeObjectTraits("f");
    writeVirtualFile(file, out);
  }

  public static void writeVirtualFile(VirtualFile file, AmfOutputStream out) {
    out.writeAmfUtf(file.getUrl());
    out.writeAmfUtf(file.getPresentableUrl());
  }

  public void initStringRegistry() throws IOException {
    StringRegistry stringRegistry = ServiceManager.getService(StringRegistry.class);
    if (stringRegistry.isEmpty()) {
      return;
    }

    beginMessage(ClientMethod.initStringRegistry);
    out.write(stringRegistry.toArray());

    blockOut.end();
  }

  public static enum ClientMethod {
    openProject, closeProject, registerLibrarySet, registerModule, registerDocumentFactory, updateDocumentFactory, openDocument, updateDocuments,
    qualifyExternalInlineStyleSource, initStringRegistry,
    registerBitmap, registerSwf;
    
    public static final int METHOD_CLASS = 0;
  }
}