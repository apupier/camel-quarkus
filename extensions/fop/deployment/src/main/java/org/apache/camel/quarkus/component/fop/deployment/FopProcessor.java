/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.quarkus.component.fop.deployment;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import javax.xml.namespace.QName;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.NativeImageFeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageProxyDefinitionBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import org.apache.batik.bridge.RhinoInterpreterFactory;
import org.apache.batik.bridge.SVG12RhinoInterpreter;
import org.apache.batik.ext.awt.image.spi.ImageTagRegistry;
import org.apache.batik.extension.svg.GlyphIterator;
import org.apache.batik.transcoder.wmf.tosvg.WMFPainter;
import org.apache.camel.quarkus.component.fop.FopRuntimeProxyFeature;
import org.apache.fop.ResourceEventProducer;
import org.apache.fop.fo.ElementMappingRegistry;
import org.apache.fop.fo.expr.PropertyException;
import org.apache.fop.fonts.Base14Font;
import org.apache.fop.image.loader.batik.ImageConverterG2D2SVG;
import org.apache.fop.image.loader.batik.ImageConverterSVG2G2D;
import org.apache.fop.image.loader.batik.ImageConverterWMF2G2D;
import org.apache.fop.image.loader.batik.ImageLoaderFactorySVG;
import org.apache.fop.pdf.PDFSignature;
import org.apache.fop.render.ImageHandlerRegistry;
import org.apache.fop.render.RendererEventProducer;
import org.apache.fop.render.RendererFactory;
import org.apache.fop.render.XMLHandlerRegistry;
import org.apache.fop.render.bitmap.BitmapRendererOption;
import org.apache.fop.render.bitmap.PNGRendererMaker;
import org.apache.fop.render.pcl.PCLPageDefinition;
import org.apache.fop.render.pdf.PDFDocumentHandlerMaker;
import org.apache.fop.render.pdf.PDFImageHandlerRawPNG;
import org.apache.fop.render.pdf.extensions.PDFExtensionHandlerFactory;
import org.apache.fop.render.ps.PSImageHandlerRawPNG;
import org.apache.fop.render.ps.PSImageHandlerSVG;
import org.apache.fop.render.rtf.RTFFOEventHandlerMaker;
import org.apache.fop.render.rtf.rtflib.rtfdoc.RtfList;
import org.apache.fop.util.ColorUtil;
import org.apache.fop.util.ContentHandlerFactoryRegistry;
import org.apache.fop.util.bitmap.JAIMonochromeBitmapConverter;
import org.apache.fop.utils.text.AdvancedMessageFormat;
import org.apache.xmlgraphics.image.loader.ImageException;
import org.apache.xmlgraphics.image.loader.impl.ImageConverterBitmap2G2D;
import org.apache.xmlgraphics.image.loader.impl.ImageConverterBuffered2Rendered;
import org.apache.xmlgraphics.image.loader.impl.ImageConverterG2D2Bitmap;
import org.apache.xmlgraphics.image.loader.impl.ImageConverterRendered2PNG;
import org.apache.xmlgraphics.image.loader.impl.ImageLoaderFactoryPNG;
import org.apache.xmlgraphics.image.loader.impl.ImageLoaderFactoryRaw;
import org.apache.xmlgraphics.image.loader.impl.PreloaderBMP;
import org.apache.xmlgraphics.image.loader.impl.PreloaderEMF;
import org.apache.xmlgraphics.image.loader.impl.PreloaderEPS;
import org.apache.xmlgraphics.image.loader.impl.PreloaderGIF;
import org.apache.xmlgraphics.image.loader.impl.PreloaderJPEG;
import org.apache.xmlgraphics.image.loader.impl.PreloaderRawPNG;
import org.apache.xmlgraphics.image.loader.impl.PreloaderTIFF;
import org.apache.xmlgraphics.image.loader.impl.imageio.ImageLoaderFactoryImageIO;
import org.apache.xmlgraphics.image.loader.impl.imageio.ImageLoaderImageIO;
import org.apache.xmlgraphics.image.loader.impl.imageio.PreloaderImageIO;
import org.apache.xmlgraphics.image.loader.spi.ImageImplRegistry;
import org.apache.xmlgraphics.image.writer.ImageWriterRegistry;
import org.apache.xmlgraphics.java2d.color.ICCColorSpaceWithIntent;
import org.apache.xmlgraphics.ps.ImageEncodingHelper;
import org.apache.xmlgraphics.ps.PSState;
import org.apache.xmlgraphics.util.uri.CommonURIResolver;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;

class FopProcessor {
    private static final String FEATURE = "camel-fop";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    NativeImageFeatureBuildItem registerRuntimeProxies() {
        return new NativeImageFeatureBuildItem(FopRuntimeProxyFeature.class);
    }

    @BuildStep
    NativeImageProxyDefinitionBuildItem registerDefinitionBuildTimeProxies() {
        return new NativeImageProxyDefinitionBuildItem(ResourceEventProducer.class.getName());
    }

    @BuildStep
    ReflectiveClassBuildItem registerForReflection(CombinedIndexBuildItem combinedIndex) {
        IndexView index = combinedIndex.getIndex();

        List<String> dtos = index.getKnownClasses().stream()
                .map(ci -> ci.name().toString())
                .filter(n -> n.endsWith("ElementMapping"))
                .sorted()
                .collect(Collectors.toList());

        dtos.add(PDFExtensionHandlerFactory.class.getName());
        dtos.add(PDFDocumentHandlerMaker.class.getName());
        dtos.add(RendererEventProducer.class.getName());
        dtos.add(IOException.class.getName());
        dtos.add(FileNotFoundException.class.getName());
        dtos.add(ImageException.class.getName());
        dtos.add(Integer.class.getName());
        dtos.add(QName.class.getName());
        dtos.add(PropertyException.class.getName());

        dtos.add(PDFImageHandlerRawPNG.class.getName());
        // TWhen launching in JVM mode, it is the Pipeline loader used, where the java stack differs with native
        dtos.add(ImageLoaderImageIO.class.getName());

        // Service ImagePreloader xmlgraphics-common
        dtos.add(PreloaderTIFF.class.getName());
        dtos.add(PreloaderGIF.class.getName());
        dtos.add(PreloaderJPEG.class.getName());
        dtos.add(PreloaderBMP.class.getName());
        dtos.add(PreloaderEMF.class.getName());
        dtos.add(PreloaderEPS.class.getName());
        dtos.add(PreloaderImageIO.class.getName());
        dtos.add(PreloaderRawPNG.class.getName());

        // service FoEventHandler
        dtos.add(RTFFOEventHandlerMaker.class.getName());

        // Service ImageLoaderFactory fop
        dtos.add(ImageLoaderFactoryPNG.class.getName());
        dtos.add(ImageLoaderFactoryImageIO.class.getName());
        dtos.add(ImageLoaderFactorySVG.class.getName());
        dtos.add(ImageLoaderFactoryRaw.class.getName());

        // Service ImageConverter fop
        dtos.add(ImageConverterSVG2G2D.class.getName());
        dtos.add(ImageConverterG2D2SVG.class.getName());
        dtos.add(ImageConverterWMF2G2D.class.getName());
        dtos.add(PNGRendererMaker.class.getName());
        dtos.add(PSImageHandlerRawPNG.class.getName());

        // Service ImageConverter xml graphics
        dtos.add(ImageConverterBuffered2Rendered.class.getName());
        dtos.add(ImageConverterG2D2Bitmap.class.getName());
        dtos.add(ImageConverterBitmap2G2D.class.getName());
        dtos.add(ImageConverterRendered2PNG.class.getName());

        // these ones to try to avoid CNFE on org.mozilla.javascript.ContextAction at build time
        dtos.add("org.mozilla.javascript.ContextAction");

        return ReflectiveClassBuildItem.builder(dtos.toArray(new String[0])).build();
    }

    @BuildStep
    void addDependencies(BuildProducer<IndexDependencyBuildItem> indexDependency) {
        indexDependency.produce(new IndexDependencyBuildItem("org.apache.xmlgraphics", "fop-core"));
        // Some SPI declared there, do we need to index it explictely?
        //indexDependency.produce(new IndexDependencyBuildItem("org.apache.xmlgraphics", "xmlgraphics-common"));
    }

    @BuildStep
    NativeImageResourceBuildItem initResources(/*Collection<ResolvedDependency> dependencies*/) {
        return new NativeImageResourceBuildItem(
                "META-INF/services/org.apache.fop.fo.ElementMapping",
                "META-INF/services/org.apache.fop.fo.FOEventHandler",
                "META-INF/services/org.apache.fop.ImageHandler",
                "META-INF/services/org.apache.fop.render.intermediate.IFDocumentHandler",
                "META-INF/services/org.apache.fop.render.Renderer",
                "META-INF/services/org.apache.xmlgraphics.image.loader.spi.ImageConverter",
                "META-INF/services/org.apache.xmlgraphics.image.loader.spi.ImageloaderFactory",
                "META-INF/services/org.apache.xmlgraphics.image.loader.spi.ImagePreloader",
                "org/apache/fop/svg/event-model.xml",
                "org/apache/fop/area/event-model.xml",
                "org/apache/fop/afp/event-model.xml",
                "org/apache/fop/render/rtf/event-model.xml",
                "org/apache/fop/render/bitmap/event-model.xml",
                "org/apache/fop/render/pdf/extensions/event-model.xml",
                "org/apache/fop/render/pdf/event-model.xml",
                "org/apache/fop/render/pcl/event-model.xml",
                "org/apache/fop/render/ps/event-model.xml",
                "org/apache/fop/render/event-model.xml",
                "org/apache/fop/event-model.xml",
                "org/apache/fop/layoutmgr/inline/event-model.xml",
                "org/apache/fop/layoutmgr/event-model.xml",
                "org/apache/fop/fo/event-model.xml",
                "org/apache/fop/fo/flow/table/event-model.xml",
                "org/apache/fop/fonts/event-model.xml",
                "org/apache/fop/accessibility/event-model.xml");
    }

    @BuildStep
    public void registerRuntimeInitializedClasses(
            CombinedIndexBuildItem combinedIndex,
            BuildProducer<RuntimeInitializedClassBuildItem> runtimeInitializedClass) {

        combinedIndex.getIndex()
                .getAllKnownSubclasses(DotName.createSimple(Base14Font.class.getName()))
                .stream().map(classInfo -> classInfo.name().toString())
                .map(RuntimeInitializedClassBuildItem::new)
                .forEach(runtimeInitializedClass::produce);

        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(ImageImplRegistry.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(ImageHandlerRegistry.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(ImageTagRegistry.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(ColorUtil.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(ICCColorSpaceWithIntent.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(PDFSignature.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(RendererFactory.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(XMLHandlerRegistry.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(ContentHandlerFactoryRegistry.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(AdvancedMessageFormat.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(ElementMappingRegistry.class.getName()));
        runtimeInitializedClass
                .produce(new RuntimeInitializedClassBuildItem("org.apache.xmlgraphics.image.codec.png.PNGImage"));

        // Random
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(RtfList.class.getName()));

        // batik
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(WMFPainter.class.getName()));

        // xmlgraphics-commons
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(CommonURIResolver.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(ImageWriterRegistry.class.getName()));

        // when activating auto-registration of serviceloader (quarkus.native.auto-service-loader-registration)
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(JAIMonochromeBitmapConverter.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem("org.apache.fop.svg.font.ComplexGlyphVector"));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(GlyphIterator.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(ImageEncodingHelper.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(PCLPageDefinition.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(PSImageHandlerSVG.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(BitmapRendererOption.class.getName()));
        runtimeInitializedClass
                .produce(new RuntimeInitializedClassBuildItem("org.apache.fop.render.bitmap.BitmapRendererConfig$1"));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(PSState.class.getName()));
        // these ones to try to avoid CNFE on org.mozilla.javascript.ContextAction at build time
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(SVG12RhinoInterpreter.class.getName()));
        runtimeInitializedClass.produce(new RuntimeInitializedClassBuildItem(RhinoInterpreterFactory.class.getName()));

    }
}
