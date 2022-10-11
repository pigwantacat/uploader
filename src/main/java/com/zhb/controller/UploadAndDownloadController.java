package com.zhb.controller;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLEncoder;
import java.util.List;

/**
 * 分片上传
 *
 * @author: zh'b
 * @date: 2021/10/6 15:03
 **/

@Controller
public class UploadAndDownloadController {

    /**
     * 编码格式
     */
    private final static String UTF8 ="utf-8";
    /**
     * 文件保存下载路径
     */
    private final static String UPLOAD_DOWNLOAD_PATH = "D:\\upload_test";

    /**
     * 服务器端实现分片上传
     * @param request
     * @param response
     * @throws Exception
     */
    @ResponseBody
    @RequestMapping("/upload")
    public void upload(HttpServletRequest request, HttpServletResponse response) throws Exception {
        //设置编码格式
        response.setCharacterEncoding(UTF8);
        //当前分片
        Integer schunk = null;
        //总分片数
        Integer schunks = null;
        //名称
        String name = null;
        //保存路径
        BufferedOutputStream os = null;
        try {
            DiskFileItemFactory factory = new DiskFileItemFactory();
            //文件超出该大小写入磁盘
            factory.setSizeThreshold(1024);
            //设置保存路径
            factory.setRepository(new File(UPLOAD_DOWNLOAD_PATH));

            //解析request
            ServletFileUpload uploaded = new ServletFileUpload(factory);
            //单个文件不超过5g
            uploaded.setFileSizeMax(5L * 1024L * 1024L * 1024L);
            //总文件不超过10g
            uploaded.setSizeMax(10L * 1024L * 1024L * 1024L);

            //解析
            List<FileItem> items = uploaded.parseRequest(request);
            //遍历所有表单属性
            for (FileItem item : items) {
                //判断是不是文件，true：表单属性，false：上传的文件
                if(item.isFormField()){
                    //当前分片
                    if("chunk".equals(item.getFieldName())){
                        schunk = Integer.parseInt(item.getString(UTF8));
                    }
                    //总分片
                    if("chunks".equals(item.getFieldName())){
                        schunks = Integer.parseInt(item.getString(UTF8));
                    }
                    //名称
                    if("name".equals(item.getFieldName())){
                        name = item.getString(UTF8);
                    }
                }
            }
            //遍历所有上传文件
            for(FileItem item : items){
                //判断是不是文件，true：表单属性，false：上传的文件
                if(!item.isFormField()){
                    //临时文件名
                    String temFileName = name;
                    //名字不能为空
                    if(name != null){
                        //当前分片为空，表示上传的是一个完整的文件
                        if(schunk != null){
                            //修改临时文件名，因为当前分片文件只是完整文件的一个分片
                            temFileName = schunk +"_"+name;
                        }
                        //查询磁盘中是否有当前分片文件
                        File temFile = new File(UPLOAD_DOWNLOAD_PATH,temFileName);
                        //断点续传，当前分片文件不在磁盘中
                        if(!temFile.exists()){
                            //写入当前分片文件到磁盘中
                            item.write(temFile);
                        }
                    }
                }
            }
            //文件合并，合并所有分片文件
            //判断是否有分片、判断是否为最后一个分片
            if(schunk != null && schunk.intValue() == schunks.intValue()-1){
                File tempFile = new File(UPLOAD_DOWNLOAD_PATH,name);
                os = new BufferedOutputStream(new FileOutputStream(tempFile));

                for(int i=0 ;i<schunks;i++){
                    //获取分片
                    File file = new File(UPLOAD_DOWNLOAD_PATH,i+"_"+name);
                    //判断当前分片是否已经就位
                    while(!file.exists()){
                        Thread.sleep(100);
                        file = new File(UPLOAD_DOWNLOAD_PATH,i+"_"+name);
                    }
                    //获取当前分片数据
                    byte[] bytes = FileUtils.readFileToByteArray(file);
                    //写入数据
                    os.write(bytes);
                    //清空缓冲流中的数据
                    os.flush();
                    //删除当前分片
                    file.delete();
                }
                //清空缓冲流中的数据
                os.flush();
            }
            response.getWriter().write("上传成功"+name);
        }finally {
            try {
                if (os!=null){
                    os.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 实现服务器端支持分片下载
     * 下载示例：http://localhost:8080/download?downloadFileName=JWT视频.mp4
     * @param request
     * @param response
     * @param downloadFileName
     * @throws Exception
     */
    @RequestMapping("/download")
    public void downLoadFile(HttpServletRequest request, HttpServletResponse response,String downloadFileName) throws Exception {
        //参数为空
        if (downloadFileName==null|| "".equals(downloadFileName)) {
            return;
        }
        //读取文件
        File file = new File(UPLOAD_DOWNLOAD_PATH+"\\"+downloadFileName);
        //设置响应编码格式
        response.setCharacterEncoding(UTF8);
        //输入流
        InputStream is = null;
        //输出流
        OutputStream os = null;
        //实现服务器端支持分片下载
        try{
            //获取文件总长度，用于分片
            long fSize = file.length();
            //设置内容类型，下载
            response.setContentType("application/x-download");
            //下载提示框显示正确的名字
            String fileName = URLEncoder.encode(file.getName(),UTF8);
            //下载提示框
            response.addHeader("Content-Disposition","attachment;filename=" + fileName);
            //告诉前端 支持分片下载
            response.setHeader("Accept-Range","bytes");
            //总长度
            response.setHeader("fSize",String.valueOf(fSize));
            //文件名称
            response.setHeader("fName",fileName);

            //进行分片操作
            //文件起始，文件已经读取了多少
            long pos = 0,last = fSize-1,sum = 0;
            //判断前端传过来的request是否支持分片下载
            if(null != request.getHeader("Range")){
                //支持断点续传
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);

                //获取该分片起始位置
                String numRange = request.getHeader("Range").replaceAll("bytes=","");
                String[] strRange = numRange.split("-");
                //http  Range bytes=100-1000   bytes=100-尾
                //等于2，表明有确定的起始位置
                if(strRange.length == 2){
                    pos = Long.parseLong(strRange[0].trim());
                    last = Long.parseLong(strRange[1].trim());
                    if(last > fSize-1){
                        last = fSize-1;
                    }
                //不等于2，表明只有确定的起点，是分片的最后一段
                }else{
                    pos = Long.parseLong(numRange.replaceAll("-","").trim());
                }
            }
            //该分片的大小
            long rangeLenght = last - pos +1;
            String contentRange = "bytes " + pos + "-" + last + "/" + fSize;
            //响应分片的起始及大小
            response.setHeader("Content-Range",contentRange);
            //响应分片长度
            response.setHeader("Content-Lenght",String.valueOf(rangeLenght));

            //输入输出数据
            os = new BufferedOutputStream(response.getOutputStream());
            is = new BufferedInputStream(new FileInputStream(file));
            //跳过前面已经读取过的分片
            is.skip(pos);
            byte[] buffer = new byte[1024];
            int lenght = 0;
            //当前分片的总数据量还未传输完
            while(sum < rangeLenght){
                //读取的起始位置，剩余长度小于等于buffer则取自身，否则取buffer的长度
                lenght = is.read(buffer,0,((rangeLenght-sum) <= buffer.length ? ((int)(rangeLenght-sum)) :  buffer.length));
                //计算已经输出的总数据量
                sum = sum+ lenght;
                //输出数据
                os.write(buffer,0,lenght);
            }
            System.out.println("下载完成");
        }finally {
            try {
                if(is != null){
                    is.close();
                }
                if(os != null){
                    os.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
