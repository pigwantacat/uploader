package com.zhb.client;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.*;
import java.net.URLDecoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 客户端分片下载
 *
 * @author: zh'b
 * @date: 2021/10/6 20:34
 **/
@RestController
public class DownloadClient {
    /**
     * 分片大小
     * 50MB
     */
    private final static long PER_PAGE = 1024L * 1024L * 50L;
    /**
     * 保存路径
     */
    private final static String DOWNPATH = "D:\\upload_test\\mytest";
    /**
     * 分片下载用的线程池，建议自己手动创建
     */
    ExecutorService pool = Executors.newFixedThreadPool(10);

    /**
     * 客户端支持分片下载
     * 下载示例：http://localhost:8080/downloadFile?downloadFileName=JWT视频.mp4
     * @param downloadFileName
     * @return
     * @throws Exception
     */
    @RequestMapping("/downloadFile")
    public String downloadFile(String downloadFileName) throws Exception {
        //下载文件名为空
        if(downloadFileName==null||"".equals(downloadFileName)){
            return "error";
        }
        //探测,试探性下载
        FileInfo fileInfo = download( 0, 10, -1, downloadFileName,null);
        //总分片数量
        long pages = fileInfo.fSize / PER_PAGE;
        //进行分片
        for(long i=0;i<=pages; i++){
            //将请求提交到线程池，实现异步
            pool.submit(new Download(i*PER_PAGE,(i+1)*PER_PAGE-1,i,downloadFileName,fileInfo.fName));
        }

        return "success";
    }

    /**
     * 储存分片信息
     */
    @AllArgsConstructor
    @NoArgsConstructor
    private static class FileInfo{
        //分片大小
        long fSize;
        //分片名
        String fName;
    }


    /**
     * 用于实现分片的异步下载
     */
    @AllArgsConstructor
    @NoArgsConstructor
    private class Download implements Runnable{
        //分片的头
        long start;
        //分片的尾
        long end;
        //第几个分片
        long page;
        //下载的文件名
        String downloadFileName;
        //文件名
        String fName;

        @Override
        public void run() {
            try {
                FileInfo info = download( start, end, page, downloadFileName,fName);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 客户端实现分片下载
     * @param start
     * @param end
     * @param page
     * @param downloadFileName
     * @param fName
     * @return
     * @throws Exception
     */
    private FileInfo download(long start,long end,long page,String downloadFileName,String fName) throws Exception {
        //断点下载
        File file = new File(DOWNPATH,page+"-"+fName);
        //不是探测文件，并且文件已经下载完成，并且文件完整
        if(page!=-1 && file.exists() && file.length()==PER_PAGE){
            return null;
        }

        //实现分片下载
        HttpClient client = HttpClients.createDefault();
        //原下载路径
        HttpGet httpGet = new HttpGet("http://127.0.0.1:8080/download?downloadFileName="+downloadFileName);
        //设置分片
        httpGet.setHeader("Range","bytes="+start+"-"+end);
        //发送请求
        HttpResponse response = client.execute(httpGet);

        //获取文件流
        HttpEntity entity = response.getEntity();
        InputStream is = entity.getContent();

        //获取分片大小
        String fSize = response.getFirstHeader("fSize").getValue();
        //获取分片名
        fName = URLDecoder.decode(response.getFirstHeader("fName").getValue(),"utf-8");

        //传输数据
        FileOutputStream fis = new FileOutputStream(file);
        byte[] buffer = new byte[1024];
        int ch =0;
        while((ch = is.read(buffer)) != -1){
            fis.write(buffer,0,ch);
        }

        //关闭传输流
        try {
            is.close();
            fis.flush();
            fis.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        //判断是否为最后一个分片
        if(end - Long.parseLong(fSize) >= 0){
            mergeFile(fName,page);
        }
        return new FileInfo(Long.parseLong(fSize), fName);
    }

    /**
     * 合并下载完成的分片文件
     * @param fName
     * @param page
     * @throws Exception
     */
    private void mergeFile(String fName, long page) throws Exception {
        //最终文件
        File tempFile = new File(DOWNPATH,fName);
        //获取相应的输出流
        BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(tempFile));

        for(int i=0 ;i<=page;i++){
            //寻找分片文件
            File file = new File(DOWNPATH,i+"-"+fName);
            //可能某一分片还未下载完成
            while(!file.exists() || (i != page && file.length() < PER_PAGE)){
                Thread.sleep(100);
            }
            byte[] bytes = FileUtils.readFileToByteArray(file);
            //输出到总文件后面
            os.write(bytes);
            os.flush();
            //删除临时文件
            file.delete();
        }

        //删除探测文件
        File file = new File(DOWNPATH,-1+"-null");
        file.delete();
        os.flush();
        try {
            os.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
