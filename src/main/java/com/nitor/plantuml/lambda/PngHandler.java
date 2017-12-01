package com.nitor.plantuml.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.nitor.plantuml.PlantUmlUtil;
import com.nitor.plantuml.lambda.exception.StatusCodeException;
import org.apache.http.HttpStatus;
import org.apache.log4j.Logger;
import org.json.simple.JSONObject;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class PngHandler extends LambdaBase implements RequestStreamHandler  {

  private final PlantUmlUtil plantUmlUtil = new PlantUmlUtil();
  private static final Logger logger = Logger.getLogger(PngHandler.class);


  @Override
  public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
    JSONObject event = parseEvent(inputStream);
    String encodedUml = getEncodedUml(event);
    try {
      ByteArrayOutputStream baos = plantUmlUtil.renderDiagram(encodedUml, DiagramType.IMAGE_PNG);
      if (isNitorStyle(event)) {
        baos = applyBackground(baos);
      }
      byte[] bytes = baos.toByteArray();
      String base64Response = Base64.getEncoder().encodeToString(bytes);
      SyntaxCheckResult syntaxCheckResult = plantUmlUtil.checkSyntax(encodedUml);
      if (!syntaxCheckResult.isError()) {
        sendOKDiagramResponse(outputStream, base64Response, DiagramType.IMAGE_PNG);
      } else {
        sendDiagramResponse(outputStream, base64Response, DiagramType.IMAGE_PNG, String.valueOf(HttpStatus.SC_UNPROCESSABLE_ENTITY));
      }
    } catch (StatusCodeException sce) {
      sendExceptionResponse(outputStream, sce);
    }
  }

  private ByteArrayOutputStream applyBackground(ByteArrayOutputStream diagramImage) {
    URL url = null;
    try {
      url = new URL("http://styleguide.nitor.com/assets/plantuml/nitorstyle.plantuml");
    } catch (MalformedURLException e) {
      e.printStackTrace();
    }
    InputStream is = null;
    try {
      is = url.openStream();
    } catch (IOException e) {
      e.printStackTrace();
    }
    try {
      BufferedReader in = new BufferedReader(new InputStreamReader(is));
      String inputLine;

      while ((inputLine = in.readLine()) != null) {
        System.out.println(inputLine);
      }
      in.close();
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      try {
        is.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    byte[] originalDiagramBytes = diagramImage.toByteArray();
    try {
      if (!Files.exists(Paths.get(System.getenv(LAMBDA_TASK_ROOT), "bg.png"))) {
        logger.error("Background image not found!");
        return diagramImage;
      }
      Path pathToTmp = Paths.get("/tmp");
      Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------");
      Path diagramImageFile = Files.createTempFile(pathToTmp, null, null);
      Files.write(diagramImageFile, originalDiagramBytes);
      Path diagramWithBackgroundFile = Files.createTempFile(Paths.get("/tmp"), null, null);
      Path tempBackgroundFile = Files.createTempFile(Paths.get("/tmp"), null, null);
      Path bgFile = Paths.get(System.getenv(LAMBDA_TASK_ROOT), "bg.png");
      Path script = Paths.get(System.getenv(LAMBDA_TASK_ROOT), "bg.sh");
      Process p = new ProcessBuilder("/bin/sh", "-x", script.toString(), diagramImageFile.toString(),
          bgFile.toString(), tempBackgroundFile.toString(), diagramWithBackgroundFile.toString())
          .redirectOutput(ProcessBuilder.Redirect.INHERIT)
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start();
      p.waitFor(5, TimeUnit.SECONDS);
      if (p.exitValue() == 0) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(Files.readAllBytes(diagramWithBackgroundFile));
        return baos;
      }
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    logger.error(String.format("Problem with background apply"));
    ByteArrayOutputStream originalImageData = new ByteArrayOutputStream();
    try {
      originalImageData.write(originalDiagramBytes);
    } catch (IOException e) {
      e.printStackTrace();
    }
    return originalImageData;
  }

}